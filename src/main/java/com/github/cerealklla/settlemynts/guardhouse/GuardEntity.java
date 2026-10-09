package com.github.cerealklla.settlemynts.guardhouse;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.github.cerealklla.cartographyr.api.Cartography;
import com.github.cerealklla.cartographyr.geo.EntityId;
import com.github.cerealklla.cartographyr.geo.GeographicEntity;
import com.github.cerealklla.cartographyr.geo.Geometry;
import com.github.cerealklla.kyt.loadout.LoadoutRecord;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.zone.PlotRecord;

import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
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
import net.minecraft.world.level.levelgen.Heightmap;
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
public class GuardEntity extends PathfinderMob implements RangedAttackMob {

    // Safety-net threshold for the underground-correction check in customServerAiStep -- "don't want
    // them going underground intentionally" (GuardPatrolAreaGoal's own target-picking already only
    // ever aims at the surface) plus "teleport back if more than 5 blocks underground" (this field),
    // explicit user request, both halves of the same ask.
    private static final int UNDERGROUND_TELEPORT_THRESHOLD_BLOCKS = 5;

    private final RangedBowAttackGoal<GuardEntity> bowGoal = new RangedBowAttackGoal<>(this, 1.0, 20, 15.0F);
    private final MeleeAttackGoal meleeGoal = new MeleeAttackGoal(this, 1.0, false);

    private UUID settlementCoreId;
    private UUID plotId;

    public GuardEntity(EntityType<? extends GuardEntity> type, Level level) {
        super(type, level);
        this.reassessWeaponGoal();
    }

    /** Called once by {@link GuardSpawnTicker} right after construction -- which plot's "Town Proper" buffer this guard patrols (see {@link #resolvePatrolArea}). */
    public void setPlotIdentity(UUID settlementCoreId, UUID plotId) {
        this.settlementCoreId = settlementCoreId;
        this.plotId = plotId;
    }

    /** This guard's owning settlement -- used by {@code GuardPatrolAreaGoal} to find the WHOLE settlement's road network, not just this guard's own plot. */
    public UUID getSettlementCoreId() {
        return settlementCoreId;
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
        this.goalSelector.addGoal(3, new GuardPatrolAreaGoal(this, 0.8));
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
        this.goalSelector.removeGoal(this.meleeGoal);
        this.goalSelector.removeGoal(this.bowGoal);
        if (getMainHandItem().getItem() instanceof BowItem) {
            this.goalSelector.addGoal(2, this.bowGoal);
        } else {
            this.goalSelector.addGoal(2, this.meleeGoal);
        }
    }

    @Override
    public void onEquipItem(EquipmentSlot slot, ItemStack oldStack, ItemStack stack) {
        super.onEquipItem(slot, oldStack, stack);
        if (slot == EquipmentSlot.MAINHAND) {
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
    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
        int blockX = Mth.floor(getX());
        int blockZ = Mth.floor(getZ());
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ);
        if (surfaceY - getY() > UNDERGROUND_TELEPORT_THRESHOLD_BLOCKS) {
            getNavigation().stop();
            teleportTo(blockX + 0.5, surfaceY, blockZ + 0.5);
        }
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.storeNullable("SettlementCoreId", UUIDUtil.CODEC, settlementCoreId);
        output.storeNullable("PlotId", UUIDUtil.CODEC, plotId);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        settlementCoreId = input.read("SettlementCoreId", UUIDUtil.CODEC).orElse(null);
        plotId = input.read("PlotId", UUIDUtil.CODEC).orElse(null);
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
}
