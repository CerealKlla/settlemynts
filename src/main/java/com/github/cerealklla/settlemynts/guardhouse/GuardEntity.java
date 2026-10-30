package com.github.cerealklla.settlemynts.guardhouse;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.github.cerealklla.cartographyr.api.Cartography;
import com.github.cerealklla.cartographyr.geo.EntityId;
import com.github.cerealklla.cartographyr.geo.GeographicEntity;
import com.github.cerealklla.cartographyr.geo.Geometry;
import com.github.cerealklla.kyt.api.Kyt;
import com.github.cerealklla.kyt.generic.GenericFamily;
import com.github.cerealklla.kyt.loadout.LoadoutRecord;
import com.github.cerealklla.settlemynts.bridge.LyfeEquipmentFamiliesBridge;
import com.github.cerealklla.settlemynts.bridge.LyfeMagicianBridge;
import com.github.cerealklla.settlemynts.bridge.LyfeSummoningBridge;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.zone.ContainerWithdraw;
import com.github.cerealklla.settlemynts.zone.PlotRecord;
import com.github.cerealklla.settlemynts.zone.ShopResource;

import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RangedBowAttackGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A spawned garrison guard -- the first real {@code Mob}/{@code PathfinderMob} anywhere in this
 * suite (confirmed via a full-suite grep before this was built; every other custom entity here is a
 * non-physical "ghost" marker). Never naturally spawned -- only ever created by {@link
 * GuardSpawnTicker}, which also fully equips it from a Kyt loadout via {@link #equipFromLoadout} and
 * binds it to its garrison's own plot via {@link #setPlotIdentity}. Patrols its own plot's real
 * "Town Proper" buffer polygon (see {@link #resolvePatrolArea}, {@code GuardPatrolAreaGoal}) --
 * not a plain fixed-radius circle, which was this feature's first, deliberately-flagged v1
 * simplification -- and fights back against hostile mobs (any {@link Enemy}, ground or flying --
 * see {@code GuardSightGoal}'s own 2026-10-08 fix) only -- deliberately never
 * targets players, no raid/PvP scope this pass (see the guard-spawning plan's own "explicitly out
 * of scope" list, not an oversight).
 *
 * <p><b>Ranged attack, added 2026-10-08</b> (real report: a guard equipped with a bow via a Kyt
 * held it but never fired) -- the original slice only ever added a plain {@code MeleeAttackGoal},
 * with no {@link RangedAttackMob} implementation at all, so a bow in the mainhand slot was purely
 * decorative. Mirrors vanilla's own {@code AbstractSkeleton} pattern exactly (confirmed against the
 * real decompiled source before writing this): {@link #bowGoal}/{@link #meleeGoal} are swapped in
 * and out of the goal selector by {@link #reassessWeaponGoal()} based on whatever's currently in the
 * mainhand slot, called from {@link #onEquipItem} every time equipment changes (so re-equipping via
 * {@link #equipFromLoadout} picks the right goal automatically) -- never added statically in {@link
 * #registerGoals()}, since that runs from {@code Mob}'s own constructor before this class's field
 * initializers exist yet, same reason vanilla's own class structures it this way. No real Arrow item
 * is needed anywhere in the guard's inventory -- {@code LivingEntity#getProjectile} defaults (via
 * NeoForge's {@code LivingGetProjectileEvent}) to an empty stack when nothing handles the event, and
 * {@code ProjectileUtil#getMobArrow} already tolerates that by falling back to a plain vanilla Arrow.
 *
 * <p><b>Follow-up real bug, same day</b>: a live report that this still didn't work on Production
 * (despite working on the Dev Server) traced to {@link #readAdditionalSaveData} -- a guard reloaded
 * from a save file (every server restart) restores its equipment via a raw
 * {@code EntityEquipment#setAll} call (confirmed against the decompiled {@code LivingEntity} source)
 * that bypasses {@code setItemSlot}/{@link #onEquipItem} entirely, so {@link #reassessWeaponGoal()}
 * never re-ran for any guard that existed before this feature shipped -- only brand-new spawns (which
 * go through {@link #equipFromLoadout}'s real {@code setItemSlot} calls) picked it up. Fixed by
 * calling {@link #reassessWeaponGoal()} again at the end of {@link #readAdditionalSaveData}, after
 * {@code super}'s call has actually populated real equipment from NBT.
 */
public class GuardEntity extends PathfinderMob implements RangedAttackMob, com.github.cerealklla.nuclyus.statbar.StatBarOwner {

    private final com.github.cerealklla.settlemynts.npc.NpcStuckWatchdog stuckWatchdog = new com.github.cerealklla.settlemynts.npc.NpcStuckWatchdog();

    private final RangedBowAttackGoal<GuardEntity> bowGoal = new RangedBowAttackGoal<>(this, 1.0, 20, 15.0F);
    private final MeleeAttackGoal meleeGoal = new MeleeAttackGoal(this, 1.0, false);
    // Lyfe's Mana feature (2026-10-20) -- see GuardSpellCastGoal's own doc for the full priority
    // design ("heal allies, then ranged spell combat, melee while on cooldown").
    private final GuardSpellCastGoal spellCastGoal = new GuardSpellCastGoal(this);

    private UUID settlementCoreId;
    private UUID plotId;
    private UUID assignedStandId;
    private net.minecraft.core.BlockPos homePos;
    // Real bug fix, 2026-10-29: a Nether-founded settlement's guards were permanently stuck
    // beelining for the nearest Nether portal and parking under it, no matter what -- see
    // GuardReturnToDimensionGoal's own doc for the full root cause (it used to hardcode "home" as
    // Level.OVERWORLD, which is simply wrong for a settlement that's genuinely built in the Nether).
    // Captured once at spawn (GuardSpawnTicker, same moment as homePos) -- whichever dimension the
    // guard was actually spawned into IS home, by definition, no settlement-specific lookup needed.
    private ResourceKey<Level> homeDimension;
    // Patrol routing (2026-10-18) -- which RoadwayStakeEntity (if any) this guard currently has
    // claimed as its final patrol destination, see GuardPatrolAreaGoal's own doc. Not persisted --
    // a claim only matters for the lifetime of one in-progress patrol leg, and every claim across
    // the whole settlement naturally clearing on a restart is harmless.
    private UUID claimedPatrolStakeId;
    // Set by GuardPatrolAreaGoal's own one-shot arrival scan (never polled continuously -- see
    // GuardExtinguishFireGoal's own doc) -- non-null tells that goal there's a real fire to go
    // extinguish right now.
    private net.minecraft.core.BlockPos pendingFireTarget;

    public GuardEntity(EntityType<? extends GuardEntity> type, Level level) {
        super(type, level);
        this.reassessWeaponGoal();
        // Required alongside OpenDoorGoal (see registerGoals) -- confirmed against the decompiled
        // source (NodeEvaluator#canOpenDoors defaults false even though canPassDoors defaults true),
        // same call Villager/Vindicator/AbstractPiglin all make in their own constructors. Without
        // this, pathfinding never even considers a closed wooden door passable, regardless of
        // whether OpenDoorGoal exists.
        this.getNavigation().setCanOpenDoors(true);
        // Real report, 2026-10-17: "NPCs keep getting stuck up in trees." Confirmed against the
        // decompiled source -- neither vanilla's own Mob nor AbstractVillager ever sets a
        // PathType.LEAVES malus at all (only FIRE/FIRE_IN_NEIGHBOR), so a tree's leaf canopy reads as
        // perfectly ordinary walkable ground to the pathfinder, same as flat terrain -- nothing stops
        // a guard patrolling wide ground from routing up a trunk into one, where the 3D maze of
        // solid-for-standing leaf blocks is easy to get lost/stuck in. A strongly negative malus
        // (BLOCKED-equivalent, matching AbstractVillager's own FIRE treatment) makes the pathfinder
        // route around a tree instead of through its canopy.
        this.setPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.LEAVES, -1.0F);
    }

    /** Called once by {@link GuardSpawnTicker} right after construction -- which plot's "Town Proper" buffer this guard patrols (see {@link #resolvePatrolArea}). */
    public void setPlotIdentity(UUID settlementCoreId, UUID plotId) {
        this.settlementCoreId = settlementCoreId;
        this.plotId = plotId;
    }

    /** Called once by {@link GuardSpawnTicker} right after construction -- which {@link GuardKitStandEntity} spawned this guard, so {@link #die} knows which stand to notify. */
    public void setAssignedStandId(UUID assignedStandId) {
        this.assignedStandId = assignedStandId;
    }

    /** This guard's own Guard Kit Stand -- used by {@link GuardReturnToDimensionGoal} to resolve a pre-existing guard's real home dimension via {@link GuardKitStandIndex}, independent of wherever the guard itself currently is. */
    public UUID assignedStandId() {
        return assignedStandId;
    }

    /** Called once by {@link GuardSpawnTicker} right after construction -- this guard's own spawn point (its assigned stand's position), the rescue destination {@link #stuckWatchdog} teleports back to if this guard ever gets stuck. */
    public void setHomePos(net.minecraft.core.BlockPos homePos) {
        this.homePos = homePos;
    }

    /** Called once by {@link GuardSpawnTicker} right after construction -- the dimension this guard actually spawned into, i.e. its settlement's real home dimension. See {@link GuardReturnToDimensionGoal#isStrandedWithNoTarget}, the one consumer. */
    public void setHomeDimension(ResourceKey<Level> homeDimension) {
        this.homeDimension = homeDimension;
    }

    /** {@code null} for a guard saved before this field existed -- {@link GuardReturnToDimensionGoal#isStrandedWithNoTarget} lazily adopts the guard's current dimension as home the first time it sees {@code null}, rather than assuming a fixed dimension. */
    public ResourceKey<Level> homeDimension() {
        return homeDimension;
    }

    /** Set by {@code GuardPatrolAreaGoal} while this guard has a {@link RoadwayStakeEntity} claimed as its final patrol destination -- {@code null} otherwise. Read by {@link #die} to release the claim if this guard dies en route. */
    public void setClaimedPatrolStakeId(UUID claimedPatrolStakeId) {
        this.claimedPatrolStakeId = claimedPatrolStakeId;
    }

    /** Set by {@code GuardPatrolAreaGoal}'s one-shot arrival scan; read and cleared by {@code GuardExtinguishFireGoal}. */
    public void setPendingFireTarget(net.minecraft.core.BlockPos pendingFireTarget) {
        this.pendingFireTarget = pendingFireTarget;
    }

    public net.minecraft.core.BlockPos pendingFireTarget() {
        return pendingFireTarget;
    }

    /** This guard's owning settlement -- used by {@code GuardPatrolAreaGoal} to find the WHOLE settlement's road network, not just this guard's own plot. */
    public UUID getSettlementCoreId() {
        return settlementCoreId;
    }

    /** This guard's own assigned plot -- used by {@code GuardPickUpItemsGoal}/{@code GuardLootDeposit} to find where to store picked-up loot. */
    public UUID plotId() {
        return plotId;
    }

    /**
     * This guard's own plot's "Town Proper" buffer polygon (the same real, already-registered
     * Cartographyr entity {@code zone.PlotGeometry#paddedBuffer} produces at Finalize time and Lyfe's
     * own HUD already labels "Town Proper" -- not recomputed here, just read). Empty if the owning
     * core/plot/buffer entity can't currently be resolved (chunk unloaded, plot gone, etc.) --
     * {@code GuardPatrolAreaGoal} simply does nothing that tick if this comes back empty, same
     * "known scaling simplification" every other plot-resolving lookup in this mod already accepts.
     */
    public Optional<Geometry.Polygon> resolvePatrolArea(ServerLevel level) {
        if (settlementCoreId == null || plotId == null) {
            return Optional.empty();
        }
        if (!(level.getEntity(settlementCoreId) instanceof GhostTownHallCoreEntity core)) {
            return Optional.empty();
        }
        PlotRecord plot = core.getPlots().stream().filter(p -> p.plotId().equals(plotId)).findFirst().orElse(null);
        if (plot == null) {
            return Optional.empty();
        }
        return Cartography.getEntity(level, new EntityId(plot.cartographyrBufferEntityId()))
                .map(GeographicEntity::geometry)
                .filter(Geometry.Polygon.class::isInstance)
                .map(Geometry.Polygon.class::cast);
    }

    /** Placeholder numbers, flagged as tunable later -- same "retune later" convention as {@link GuardhouseConstants#capacityForTier}. */
    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.ATTACK_DAMAGE, 4.0)
                .add(Attributes.FOLLOW_RANGE, 24.0);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(1, new FloatGoal(this));
        // Priority 2 (melee vs. bow) is deliberately NOT added here -- see reassessWeaponGoal().
        // Also priority 2 -- a Guard stranded outside its home dimension with nothing to fight or
        // heal paths back to the nearest portal instead of patrolling nonsensically; see that
        // class's own doc for why sharing this tier with the weapon/spell goals is safe.
        this.goalSelector.addGoal(2, new GuardReturnToDimensionGoal(this, 0.8));
        // "Put out fires they can see... priority 1 upon reaching a target, then looking for
        // enemies, then picking a new stake" (explicit user spec, 2026-10-30) -- priority 1, strictly
        // above GuardReturnToDimensionGoal/the weapon-or-spell goal above (priority 2, which already
        // internally orders heal<50% -> enemies -> heal-topoff, unchanged) and GuardPatrolAreaGoal
        // below (priority 3) -- vanilla's own GoalSelector priority-preemption rules do the rest. See
        // GuardExtinguishFireGoal's own doc for why this never polls continuously.
        this.goalSelector.addGoal(1, new GuardExtinguishFireGoal(this));
        // "They should still pick up items, but do that last before moving to a new stake" (explicit
        // user spec, 2026-10-30) -- registered BEFORE GuardPatrolAreaGoal below despite sharing the
        // same priority number (3): confirmed against the decompiled GoalSelector/WrappedGoal source
        // that same-priority goals never preempt each other (canBeReplacedBy requires a STRICTLY
        // lower priority number) and are evaluated in plain insertion order each tick, so whichever
        // is added first gets first claim on the shared Flag.MOVE slot at an idle moment -- the
        // previous registration order (Patrol added first) meant Patrol always re-picked a new stake
        // immediately and this goal never got a real chance to run at all.
        this.goalSelector.addGoal(3, new GuardPickUpItemsGoal(this));
        this.goalSelector.addGoal(3, new GuardPatrolAreaGoal(this, 0.8));
        // Real wooden doors (explicit live report, 2026-10-16: "not opening/closing doors/gates when
        // they get stuck") -- same priority/shape vanilla's own Vindicator uses for the identical
        // goal. `setCanOpenDoors(true)` on the navigation is the other required half (see the
        // constructor) -- without it, pathfinding itself never plans through a closed door at all,
        // regardless of this goal existing.
        this.goalSelector.addGoal(3, new net.minecraft.world.entity.ai.goal.OpenDoorGoal(this, true));
        // Fence gates have no vanilla equivalent at all -- see GuardGateOpenGoal's own doc for why
        // this is a separate, custom goal rather than reusing OpenDoorGoal.
        this.goalSelector.addGoal(3, new GuardGateOpenGoal(this));
        this.goalSelector.addGoal(4, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(5, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new GuardSightGoal(this));
    }

    /**
     * Swaps {@link #meleeGoal}/{@link #bowGoal} into goal-selector priority 2 based on whatever's
     * currently in the mainhand slot -- same pattern/call sites as vanilla's {@code
     * AbstractSkeleton#reassessWeaponGoal}. Safe to call before {@code settlementCoreId}/{@code
     * plotId} are set (constructor time) and repeatedly during {@link #equipFromLoadout} (once per
     * slot, via {@link #onEquipItem}) -- idempotent either way.
     */
    private void reassessWeaponGoal() {
        if (this.level() == null || this.level().isClientSide()) {
            return;
        }
        if (com.github.cerealklla.settlemynts.bridge.LyfeMagicianBridge.isLoaded()) {
            // Mainhand/offhand equipment is exactly "this guard's available spells changed" (today,
            // the only spell source is a Nature Staff/Focus) -- clear any cached
            // canSparkOfLifeAffect results so a stale "can't affect this target" doesn't linger.
            com.github.cerealklla.settlemynts.bridge.LyfeMagicianBridge.invalidateSpellTargetCache(this);
        }
        this.goalSelector.removeGoal(this.meleeGoal);
        this.goalSelector.removeGoal(this.bowGoal);
        this.goalSelector.removeGoal(this.spellCastGoal);
        if (getMainHandItem().getItem() instanceof BowItem) {
            this.goalSelector.addGoal(2, this.bowGoal);
        } else if (LyfeMagicianBridge.isLoaded() && (LyfeMagicianBridge.isNatureStaff(getMainHandItem())
                || LyfeMagicianBridge.hasOffhandSpell(getOffhandItem()))) {
            // Spell-cast at priority 2 (tried first); melee at priority 3 as a pure fallback -- the
            // GoalSelector itself handles "melee while on cooldown" for free, since spellCastGoal's
            // own canUse() returning false (nothing to heal, spell unavailable) lets the
            // lower-priority melee goal start instead. See GuardSpellCastGoal's own doc.
            this.goalSelector.addGoal(2, this.spellCastGoal);
            this.goalSelector.addGoal(3, this.meleeGoal);
        } else {
            this.goalSelector.addGoal(2, this.meleeGoal);
        }
    }

    @Override
    public void onEquipItem(EquipmentSlot slot, ItemStack oldStack, ItemStack stack) {
        super.onEquipItem(slot, oldStack, stack);
        // Widened to OFFHAND, 2026-10-21 (Lyfe's new Focus item, design doc Section 25) --
        // equipping/swapping a spell-granting Focus must retrigger reassessWeaponGoal() the same
        // way a mainhand Nature Staff already does, or a Guard given one via its Kit Stand never
        // picks up spell-casting behavior at all.
        if (slot == EquipmentSlot.MAINHAND || slot == EquipmentSlot.OFFHAND) {
            reassessWeaponGoal();
        }
    }

    /** Mirrors {@code AbstractSkeleton#performRangedAttack} (confirmed against the real decompiled source). */
    @Override
    public void performRangedAttack(LivingEntity target, float power) {
        ItemStack bowItem = getMainHandItem();
        ItemStack projectileStack = getProjectile(bowItem);
        AbstractArrow arrow = ProjectileUtil.getMobArrow(this, projectileStack, power, bowItem);
        double xd = target.getX() - getX();
        double yd = target.getY(0.3333333333333333) - arrow.getY();
        double zd = target.getZ() - getZ();
        double distanceToTarget = Math.sqrt(xd * xd + zd * zd);
        if (level() instanceof ServerLevel serverLevel) {
            Projectile.spawnProjectileUsingShoot(
                    arrow, serverLevel, projectileStack, xd, yd + distanceToTarget * 0.2F, zd, 1.6F,
                    14 - serverLevel.getDifficulty().getId() * 4);
        }
        playSound(SoundEvents.SKELETON_SHOOT, 1.0F, 1.0F / (getRandom().nextFloat() * 0.4F + 0.8F));
    }

    /**
     * Guards never damage each other -- explicit request. Checks the damage source's direct AND
     * root-cause entity (an arrow's direct entity is the arrow itself, not the guard that fired it),
     * rather than relying on target selection alone (which only governs who a guard chooses to
     * attack, not what actually connects).
     */
    @Override
    public boolean isInvulnerableTo(ServerLevel level, net.minecraft.world.damagesource.DamageSource source) {
        if (source.getDirectEntity() instanceof GuardEntity || source.getEntity() instanceof GuardEntity) {
            return true;
        }
        return super.isInvulnerableTo(level, source);
    }

    /**
     * Safety net for "teleport back to the surface if more than 5 blocks underground" -- runs every
     * server tick regardless of which goal is currently active, so it still catches a guard that
     * ended up underground via combat-chasing a monster into a hole, not just via its own patrol
     * target-picking (which, separately, never intentionally aims below the surface -- see {@code
     * GuardPatrolAreaGoal}).
     */
    /**
     * Notifies this guard's own Guard Kit Stand it's dead (clearing {@code assignedGuardId}, so
     * {@link GuardSpawnTicker} knows the slot is open again) and attempts fast-respawn food funding
     * from the plot's Guardhouse -- Guard Kit Stand overhaul, 2026-10-16, replacing the old push-based
     * {@code GuardhouseBlockEntity#clearGuardIdIfMatches} call (see that method's own now-removed doc
     * for why a push-based clear, not a poll, is the right shape here: an entity can only actually
     * die while its own chunk is loaded and ticking, so this always fires at the real moment of
     * death, never mistaking "out of render range" for "dead").
     */
    @Override
    public void die(net.minecraft.world.damagesource.DamageSource damageSource) {
        super.die(damageSource);
        // Patrol routing (2026-10-18): release a claimed final-destination RoadwayStakeEntity right
        // now rather than letting it sit reserved for the rest of its self-healing TTL -- see
        // GuardPatrolAreaGoal's own doc for why Goal#stop() can't be relied on here (a dead entity
        // generally stops ticking before the goal selector would ever call it).
        if (claimedPatrolStakeId != null && level() instanceof ServerLevel claimLevel
                && claimLevel.getEntity(claimedPatrolStakeId) instanceof com.github.cerealklla.settlemynts.roadway.RoadwayStakeEntity claimedStake) {
            claimedStake.clearClaim();
        }
        if (plotId == null || assignedStandId == null || !(level() instanceof ServerLevel serverLevel)) {
            return;
        }
        // Real bug fix, 2026-10-29 -- see GuardPendingDeathIndex's own doc for the full incident.
        // Always reliable (a plain Map write, no chunk/entity/dimension dependency at all), unlike
        // the old direct stand-notification this replaced, which silently no-op'd whenever the
        // stand's own chunk wasn't loaded at this exact instant.
        long respawnReadyAtGameTime = serverLevel.getGameTime() + fundFastRespawn(serverLevel);
        GuardPendingDeathIndex.get(serverLevel.getServer()).recordDeath(assignedStandId, respawnReadyAtGameTime);
    }

    /** Resolves the plot's Tier and its Guardhouse's food store, attempts {@link GuardRespawnFunding#tryFundFastRespawn}, and returns how many ticks until the stand should be allowed to spawn a replacement. */
    private long fundFastRespawn(ServerLevel serverLevel) {
        if (!(serverLevel.getEntity(settlementCoreId) instanceof GhostTownHallCoreEntity core)) {
            return GuardRespawnFunding.NO_FOOD_RESPAWN_TICKS;
        }
        PlotRecord plot = core.getPlots().stream().filter(p -> p.plotId().equals(plotId)).findFirst().orElse(null);
        if (plot == null) {
            return GuardRespawnFunding.NO_FOOD_RESPAWN_TICKS;
        }
        // Resolved live via constructionBoxId, not the stale plot.boxPos() snapshot (2026-10-16 fix).
        int tier = net.neoforged.fml.ModList.get().isLoaded("blueprynts")
                ? com.github.cerealklla.settlemynts.bridge.BlueprintsConstructionBridge.resolveTier(serverLevel, plot)
                : 0;
        boolean funded = GuardhouseIndex.get(serverLevel.getServer()).get(plotId)
                .filter(pos -> serverLevel.getServer().getLevel(pos.dimension()) != null
                        && serverLevel.getServer().getLevel(pos.dimension()).getBlockEntity(pos.pos()) instanceof GuardhouseBlockEntity)
                .map(pos -> (GuardhouseBlockEntity) serverLevel.getServer().getLevel(pos.dimension()).getBlockEntity(pos.pos()))
                .map(guardhouse -> GuardRespawnFunding.tryFundFastRespawn(guardhouse, tier))
                .orElse(false);
        return funded ? GuardRespawnFunding.FUNDED_RESPAWN_TICKS : GuardRespawnFunding.NO_FOOD_RESPAWN_TICKS;
    }

    /**
     * All the old heightmap-scan-based rescue logic (underground-correction via a raw
     * {@code MOTION_BLOCKING_NO_LEAVES} read, the home-radius exemption that workaround needed, and
     * {@code npc.NpcTreetopEscape}'s own treetop-stranding check) is gone, 2026-10-22 -- see {@link
     * com.github.cerealklla.settlemynts.npc.NpcStuckWatchdog}'s own doc for why: every one of those
     * checks could, and eventually did, misfire and teleport a guard UP onto a roof or tree canopy
     * instead of rescuing it from one. This instead just asks "has this guard gone anywhere lately
     * while it's supposedly trying to," with a rescue destination (its own assigned stand) that's
     * always known-safe rather than freshly (and fallibly) computed.
     */
    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
        stuckWatchdog.tick(this, homePos);
    }

    /**
     * Bound Allay in a Bottle / Summoning Stone item(s) the Kyt's hotbar specified (explicit user
     * spec, 2026-10-25), tracked internally -- never visibly equipped (no vanilla EquipmentSlot of
     * its own; would otherwise compete with a real weapon for MAINHAND). "The Kyt's hotbar already
     * has room" for more than one, so this is a list, not a single slot. A companion specified via
     * the Kyt's own {@code offhand} field instead is a different case entirely -- "if the Kyt demands
     * that the item be held in the off-hand then that's where it will be at all times" (explicit user
     * spec) -- it's equipped normally into real OFFHAND by the unmodified {@link #withdrawAndEquip}
     * call in {@link #equipFromLoadoutUsingBoxes}, not tracked here; see {@link #trackedCompanionStacks}.
     */
    private final List<ItemStack> companionItems = new java.util.ArrayList<>();

    /**
     * "Only events. Guard spawns, summon all companions. Companion Dies, set timer on guard to
     * re-summon. No unnecessary persistent ticks" (explicit user spec, 2026-10-25) -- deliberately
     * NOT a per-guard, per-tick check (like {@link #customServerAiStep}, rejected for exactly this
     * reason). Called once, as a real event, from two places: {@link #equipFromLoadoutUsingBoxes}
     * right after spawn-equip (the "Guard spawns, summon all companions" event), and {@link
     * GuardSpawnTicker}'s own already-existing periodic per-stand scan for an already-alive guard
     * (the closest thing to a "timer" available without a new scheduling mechanism -- the Guard's own
     * death/respawn timer is already driven the identical way, via {@code
     * GuardKitStandEntity#respawnReadyAtGameTime} checked on that same scan, not a dedicated ticker).
     * {@link LyfeSummoningBridge#tickCompanion} is itself both the summon trigger and the death/
     * cooldown tracker -- noticing a previously-summoned companion has died starts its cooldown right
     * there (mirrors {@code AllayDeathListener}), and a companion with no cooldown pending summons
     * immediately, so one call per tracked stack covers "just spawned," "died, start cooldown," and
     * "cooldown elapsed, re-summon" with no separate code path for any of the three.
     */
    void recheckCompanions(ServerLevel level) {
        if (!LyfeSummoningBridge.isLoaded()) {
            return;
        }
        for (ItemStack stack : trackedCompanionStacks()) {
            LyfeSummoningBridge.tickCompanion(level, stack, getUUID(), getX(), getY(), getZ(), getYRot());
        }
    }

    /** {@link #companionItems} plus the real OFFHAND stack, if that happens to be a companion item too (see {@link #companionItems}'s own doc). */
    private List<ItemStack> trackedCompanionStacks() {
        ItemStack offhand = getItemBySlot(EquipmentSlot.OFFHAND);
        if (!offhand.isEmpty() && LyfeSummoningBridge.isLoaded() && LyfeSummoningBridge.isCompanionItem(offhand)) {
            List<ItemStack> combined = new java.util.ArrayList<>(companionItems);
            combined.add(offhand);
            return combined;
        }
        return companionItems;
    }

    /**
     * Scans {@code record.hotbar()} for every companion-type entry, withdraws each from {@code
     * plotBoxes}, and tracks it in {@link #companionItems} -- called at spawn-equip time, and again
     * whenever {@link #equipMissingFromLoadoutUsingBoxes} reacts to a real box-contents-changed event
     * (so a companion listed in the Kyt but out of stock at spawn still gets picked up later, without
     * polling). Safe to call repeatedly: skips any candidate whose item type is already tracked, so a
     * second call never double-withdraws the same companion.
     */
    private void withdrawCompanionsFromHotbar(List<Container> plotBoxes, LoadoutRecord record) {
        if (!LyfeSummoningBridge.isLoaded()) {
            return;
        }
        for (ItemStack candidate : record.hotbar()) {
            if (candidate.isEmpty() || !LyfeSummoningBridge.isCompanionItem(candidate)) {
                continue;
            }
            boolean alreadyTracked = companionItems.stream().anyMatch(stack -> stack.getItem() == candidate.getItem());
            if (alreadyTracked) {
                continue;
            }
            Identifier itemId = BuiltInRegistries.ITEM.getKey(candidate.getItem());
            List<ItemStack> taken = ContainerWithdraw.drain(plotBoxes, ShopResource.ofItem(itemId), 1);
            if (!taken.isEmpty()) {
                companionItems.add(taken.get(0));
            }
        }
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.storeNullable("SettlementCoreId", UUIDUtil.CODEC, settlementCoreId);
        output.storeNullable("PlotId", UUIDUtil.CODEC, plotId);
        output.storeNullable("AssignedStandId", UUIDUtil.CODEC, assignedStandId);
        output.storeNullable("HomePos", net.minecraft.core.BlockPos.CODEC, homePos);
        output.storeNullable("HomeDimension", ResourceKey.codec(Registries.DIMENSION), homeDimension);
        output.store("CompanionItems", ItemStack.OPTIONAL_CODEC.listOf(), companionItems);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        settlementCoreId = input.read("SettlementCoreId", UUIDUtil.CODEC).orElse(null);
        plotId = input.read("PlotId", UUIDUtil.CODEC).orElse(null);
        assignedStandId = input.read("AssignedStandId", UUIDUtil.CODEC).orElse(null);
        homePos = input.read("HomePos", net.minecraft.core.BlockPos.CODEC).orElse(null);
        homeDimension = input.read("HomeDimension", ResourceKey.codec(Registries.DIMENSION)).orElse(null);
        companionItems.clear();
        companionItems.addAll(input.read("CompanionItems", ItemStack.OPTIONAL_CODEC.listOf()).orElse(List.of()));
        // Real bug found live, 2026-10-08: a guard that existed before the ranged-attack fix shot
        // nothing on Production even after redeploying, while a freshly-spawned guard worked fine on
        // the Dev Server. Root cause: LivingEntity#readAdditionalSaveData (just called via super,
        // above) restores equipment with a raw EntityEquipment#setAll, confirmed against the
        // decompiled source to never call setItemSlot/onEquipItem at all -- so reloading a saved guard
        // from disk (every server restart) silently skips reassessWeaponGoal() entirely, leaving it
        // stuck on whatever goal the constructor picked (always meleeGoal, since equipment is still
        // empty at that point -- see the constructor). Calling it again here, now that super's call
        // has actually populated real equipment from NBT, fixes every already-saved guard on its next
        // load -- not just newly-spawned ones.
        reassessWeaponGoal();
    }

    /**
     * Equips this guard directly from a saved Kyt loadout. **Mainhand** is the first non-empty stack
     * found in {@code record.hotbar()} -- a Kyt author can put their intended weapon in any hotbar
     * slot and it's picked up, the simplest usable heuristic (flagged as such, not a precise
     * "find the weapon" classifier). **Drop chance is zeroed on every equipped slot** -- a respawned
     * guard just re-reads this same Kyt record fresh from storage next time, so a dead guard
     * additionally dropping its equipped copy on the ground would let a player farm infinite gear by
     * repeatedly killing guards.
     */
    public void equipFromLoadout(LoadoutRecord record) {
        setItemSlot(EquipmentSlot.HEAD, record.helmet().copy());
        setItemSlot(EquipmentSlot.CHEST, record.chestplate().copy());
        setItemSlot(EquipmentSlot.LEGS, record.leggings().copy());
        setItemSlot(EquipmentSlot.FEET, record.boots().copy());
        setItemSlot(EquipmentSlot.OFFHAND, record.offhand().copy());
        for (ItemStack stack : record.hotbar()) {
            if (!stack.isEmpty()) {
                setItemSlot(EquipmentSlot.MAINHAND, stack.copy());
                break;
            }
        }
        for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                EquipmentSlot.FEET, EquipmentSlot.OFFHAND, EquipmentSlot.MAINHAND)) {
            setDropChance(slot, 0.0F);
        }
        setPersistenceRequired();
        setCanPickUpLoot(false);
    }

    /**
     * Guard Kit Stand overhaul (explicit user spec, 2026-10-16): "Every time a guard is spawned it
     * will deduct the gear on it's assigned Guard Kit Stand from boxes within the plot. If there is
     * no gear on-hand, that guard will spawn with anything that was available." Each of the 6
     * potential loadout slots (helmet/chest/legs/boots/offhand/mainhand) is checked independently via
     * {@link ContainerWithdraw#drain} (best-effort, matched by plain item id -- {@link
     * ShopResource#ofItem} ignores durability/enchantments, since no real gear-Tier item catalog
     * exists anywhere in this suite to match more precisely against) -- a slot with no matching item
     * in the boxes simply spawns unequipped, not a hard "needs the full kit" gate. Mainhand tries
     * every non-empty hotbar stack in {@code record}, in order, stopping at the first one actually
     * available in the boxes -- see {@link #withdrawAndEquipFirstAvailable}'s own doc for the real
     * bug this fixes (a Kyt with a Bow in hand-slot 1 and a Sword in hand-slot 2 would never equip
     * the Sword even with plenty in stock, if the Bow alone was simply out of stock).
     */
    public void equipFromLoadoutUsingBoxes(LoadoutRecord record, List<Container> plotBoxes) {
        withdrawAndEquip(plotBoxes, record.helmet(), EquipmentSlot.HEAD);
        withdrawAndEquip(plotBoxes, record.chestplate(), EquipmentSlot.CHEST);
        withdrawAndEquip(plotBoxes, record.leggings(), EquipmentSlot.LEGS);
        withdrawAndEquip(plotBoxes, record.boots(), EquipmentSlot.FEET);
        withdrawAndEquip(plotBoxes, record.offhand(), EquipmentSlot.OFFHAND);
        withdrawAndEquipFirstAvailable(plotBoxes, record, EquipmentSlot.MAINHAND);
        for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                EquipmentSlot.FEET, EquipmentSlot.OFFHAND, EquipmentSlot.MAINHAND)) {
            setDropChance(slot, 0.0F);
        }
        setPersistenceRequired();
        setCanPickUpLoot(false);

        // "Guard spawns, summon all companions" (explicit user spec, 2026-10-25) -- see
        // companionItems'/recheckCompanions' own doc.
        withdrawCompanionsFromHotbar(plotBoxes, record);
        if (level() instanceof ServerLevel serverLevel) {
            recheckCompanions(serverLevel);
        }
    }

    /**
     * Resolves a plain real item's template normally (matched by id, same as always), or -- if
     * {@code template} is one of Kyt's Generic placeholders (explicit user spec, 2026-10-25: "Use
     * any Sword"/etc.) -- walks that family's real items lowest tier first, equipping the first one
     * actually in stock ("the lowest tier available of the generic first"). {@link Kyt#genericFamilyOf}
     * is called unguarded, same as this file's existing unconditional {@link LoadoutRecord} usage --
     * reaching this method at all already requires a real Kyt loadout, so Kyt is necessarily loaded.
     */
    private boolean withdrawAndEquip(List<Container> plotBoxes, ItemStack template, EquipmentSlot slot) {
        if (template.isEmpty()) {
            return false;
        }
        Optional<GenericFamily> family = Kyt.genericFamilyOf(template);
        if (family.isPresent()) {
            return withdrawGenericAndEquip(plotBoxes, family.get(), slot);
        }
        Identifier itemId = BuiltInRegistries.ITEM.getKey(template.getItem());
        List<ItemStack> taken = ContainerWithdraw.drain(plotBoxes, ShopResource.ofItem(itemId), 1);
        if (taken.isEmpty()) {
            return false;
        }
        setItemSlot(slot, taken.get(0));
        return true;
    }

    private boolean withdrawGenericAndEquip(List<Container> plotBoxes, GenericFamily family, EquipmentSlot slot) {
        if (!LyfeEquipmentFamiliesBridge.isLoaded()) {
            return false;
        }
        for (Identifier candidateId : LyfeEquipmentFamiliesBridge.idsLowToHigh(family)) {
            List<ItemStack> taken = ContainerWithdraw.drain(plotBoxes, ShopResource.ofItem(candidateId), 1);
            if (!taken.isEmpty()) {
                setItemSlot(slot, taken.get(0));
                return true;
            }
        }
        return false;
    }

    /**
     * Real report, 2026-10-18 ("The Kyt has both a bow and a sword; there are no bows available but
     * there are swords and no guards seem to be taking the swords, fresh or already alive"): both
     * {@link #equipFromLoadoutUsingBoxes}/{@link #equipMissingFromLoadoutUsingBoxes} used to just
     * grab {@code record.hotbar()}'s FIRST non-empty stack as "the" mainhand candidate and attempt
     * that one alone -- correct as a simple "what does this Kyt author consider their weapon"
     * heuristic for an unconditional equip ({@link #equipFromLoadout}, which has no concept of
     * "unavailable" since it never checks box stock), but wrong once box availability enters the
     * picture: if that one candidate (the Bow, in hand-slot 1) happens to be out of stock, the old
     * code gave up on mainhand ENTIRELY rather than trying the next hotbar candidate (the Sword, in
     * hand-slot 2) -- even with the boxes genuinely full of swords. Now tries every non-empty hotbar
     * stack in order, stopping at the first one {@link #withdrawAndEquip} actually finds in stock.
     */
    private void withdrawAndEquipFirstAvailable(List<Container> plotBoxes, LoadoutRecord record, EquipmentSlot slot) {
        for (ItemStack candidate : record.hotbar()) {
            if (!candidate.isEmpty() && withdrawAndEquip(plotBoxes, candidate, slot)) {
                return;
            }
        }
    }

    /**
     * "Any time a guardhouse's inventory changes, check all guards for that guardhouse and if they
     * are missing gear which is now available equip them with it" (explicit user spec, 2026-10-17).
     * Same per-slot withdraw as {@link #equipFromLoadoutUsingBoxes}, but only for a slot this guard
     * doesn't already have something equipped in -- never replaces gear it already has, and never
     * double-withdraws from the boxes for a slot that's already filled. Called periodically by
     * {@code GuardSpawnTicker} for every already-spawned guard (there's no real "container changed"
     * event for a plain world chest to react to directly, so a short poll interval stands in for it,
     * same as every other reactive-feeling mechanic in this mod).
     */
    public void equipMissingFromLoadoutUsingBoxes(LoadoutRecord record, List<Container> plotBoxes) {
        withdrawAndEquipIfMissing(plotBoxes, record.helmet(), EquipmentSlot.HEAD);
        withdrawAndEquipIfMissing(plotBoxes, record.chestplate(), EquipmentSlot.CHEST);
        withdrawAndEquipIfMissing(plotBoxes, record.leggings(), EquipmentSlot.LEGS);
        withdrawAndEquipIfMissing(plotBoxes, record.boots(), EquipmentSlot.FEET);
        withdrawAndEquipIfMissing(plotBoxes, record.offhand(), EquipmentSlot.OFFHAND);
        if (getItemBySlot(EquipmentSlot.MAINHAND).isEmpty()) {
            withdrawAndEquipFirstAvailable(plotBoxes, record, EquipmentSlot.MAINHAND);
        }
        // Box contents just changed (this method's own trigger, see GuardEquipBoxChangeListener) --
        // a companion that was out of stock at spawn may be available now; this event is also a
        // reasonable point to retry summoning one that's tracked but still on/past cooldown.
        withdrawCompanionsFromHotbar(plotBoxes, record);
        if (level() instanceof ServerLevel serverLevel) {
            recheckCompanions(serverLevel);
        }
    }

    private void withdrawAndEquipIfMissing(List<Container> plotBoxes, ItemStack template, EquipmentSlot slot) {
        if (!getItemBySlot(slot).isEmpty()) {
            return;
        }
        withdrawAndEquip(plotBoxes, template, slot);
    }
}
