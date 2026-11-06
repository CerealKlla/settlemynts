package com.github.cerealklla.settlemynts.tavern;

import java.util.UUID;

import com.github.cerealklla.settlemynts.bridge.LyfeMagicianBridge;
import com.github.cerealklla.settlemynts.bridge.LyfeSummoningBridge;

import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
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
 * A seated Tavern NPC -- design doc Section 17: "targeted spawn locations for new NPCs, when they
 * spawn they will be seated on the stools and never get up." "Never get up" means registering NO
 * movement goal at all, not even a work goal. {@link #hireRequirement} and {@link #tier} are rolled
 * once at spawn ({@code TavernDailySpawnTicker}); the NPC's own current stats (set by {@code
 * TavernStatRoller} at that same moment) are what actually get captured into the eventual Companion
 * Token -- nothing extra needs saving here for that part. Starts with empty equipment slots (no gear
 * roller exists any more, removed 2026-11-01 -- see {@code TavernDailySpawnTicker#spawnNpc}'s own
 * doc for the exploit that caused this).
 *
 * <p><b>A plain humanoid, not a Villager, 2026-10-31 correction</b> (real user feedback: "they're
 * all Villagers. I'd like them to be any humanoid, though most of the time human like Steve" -- and
 * separately, "the NPCs look like they're standing on the stools, not sitting"). Switched to a plain
 * {@code extends PathfinderMob} (same base {@code guardhouse.GuardEntity} already uses) rendered via
 * a new {@code client.TavernNpcRenderer} built on {@code HumanoidMobRenderer} -- see that class's own
 * doc, a direct mirror of {@code guardhouse.GuardRenderer}'s own "vanilla Zombie body mesh + the real
 * Steve texture" technique (confirmed against the real decompiled {@code HumanoidModel#setupAnim}:
 * it bends the legs/arms into a genuine seated pose whenever {@code state.isPassenger} is true --
 * built into vanilla for any humanoid-rendered entity, since players already get this riding a boat/
 * minecart -- so switching off {@code VillagerModel} fixes the "standing not sitting" visual for
 * free, with zero new animation code, as a direct side effect of {@code TavernDailySpawnTicker}
 * already mounting this NPC onto its stool's real seat).
 *
 * <p><b>Reuses Lyfe's real stool furniture</b> (same-day correction, see that class's own context)
 * -- {@code TavernDailySpawnTicker} mounts a freshly spawned NPC onto the same {@code
 * com.github.cerealklla.lyfe.rest.SeatEntity} mount point a player sitting on that same stool would
 * use, via {@code bridge.LyfeTavernBridge#seatOnStool}.
 *
 * <p><b>Real combat/follow AI for the summoned Companion, 2026-11-01</b> (explicit user spec: "Use
 * the same steps as town guards, e.g. heal allies, look for enemies, etc., just replace 'move to
 * road stake' with 'move to a place on the ground near player'"). {@link #stoolPos} doubles as the
 * seated-vs-companion switch: {@link #setIdentity} (only ever called by {@code
 * TavernDailySpawnTicker} for a fresh stool spawn) sets it non-null, and a token-summoned companion
 * (via Lyfe's {@code CompanionTokenItem#use}, which never calls {@link #setIdentity} at all) leaves
 * it permanently {@code null} -- every new goal added below checks this and defers entirely while
 * {@code stoolPos != null}, so a seated patron's "never get up" guarantee is completely unaffected.
 * {@link #bowGoal}/{@link #meleeGoal}/{@link #spellCastGoal}/{@link #reassessWeaponGoal} are a direct
 * copy of {@code guardhouse.GuardEntity}'s own weapon-swap mechanism; {@link CompanionSightGoal}/
 * {@link CompanionSpellCastGoal}/{@link CompanionFollowPlayerGoal} are direct copies of that class's
 * own {@code GuardSightGoal}/{@code GuardSpellCastGoal}/{@code GuardPatrolAreaGoal} -- see each of
 * those classes' own doc for what changed.
 */
public class TavernNpcEntity extends PathfinderMob implements RangedAttackMob, com.github.cerealklla.nuclyus.statbar.StatBarOwner {

    private final CompanionRangedAttackGoal bowGoal = new CompanionRangedAttackGoal(this, 1.0, 20, 15.0F);
    private final CompanionMeleeAttackGoal meleeGoal = new CompanionMeleeAttackGoal(this, 1.0, false);
    private final CompanionSpellCastGoal spellCastGoal = new CompanionSpellCastGoal(this);

    /**
     * Mounted combat movement speed -- real report, 2026-11-03: "when a companion riding a horse
     * targets an enemy they should move at mounted running speed to get to the target, not barely
     * walking faster than a player walks." Same value as {@code CompanionFollowPlayerGoal
     * #MOUNTED_SPEED_MODIFIER}, which this constant replaces that class's own private copy of (see
     * {@link #mover()}'s own doc for the shared root cause). Used by {@link CompanionMeleeAttackGoal}/
     * {@link CompanionRangedAttackGoal}/{@link CompanionSpellCastGoal} in place of their own base
     * {@code speedModifier} whenever {@link #mover()} resolves to a mount -- on foot, each goal's own
     * base speed (passed in at construction / {@code 1.0}) is unaffected, since that case was never
     * reported broken.
     */
    public static final double MOUNTED_COMBAT_SPEED_MODIFIER = 2.5;

    /**
     * Whether this Tavern NPC is female -- added 2026-11-05, real user request to give NPCs a real
     * gender. Tavern NPCs have no {@code npc.NpcIdentity}/name/voice system at all (unlike the other 5
     * roles), so this is a plain coin-flip ({@code npc.NpcGender#roll()}) rolled once in {@link
     * #setIdentity}, not resolved from any persisted identity. Read by {@code tavern.client.TavernNpcRenderer}
     * to pick which real vanilla default skin (Steve vs. Alex) to render.
     */
    private static final net.minecraft.network.syncher.EntityDataAccessor<Boolean> DATA_FEMALE =
            net.minecraft.network.syncher.SynchedEntityData.defineId(TavernNpcEntity.class, net.minecraft.network.syncher.EntityDataSerializers.BOOLEAN);

    private GlobalPos stoolPos;
    private UUID settlementCoreId;
    private UUID plotId;
    private int tier = 1;
    private HireRequirement hireRequirement;

    public TavernNpcEntity(EntityType<? extends TavernNpcEntity> type, Level level) {
        super(type, level);
        skipDropExperience();
        this.reassessWeaponGoal();
    }

    @Override
    protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_FEMALE, false);
    }

    public boolean isFemale() {
        return entityData.get(DATA_FEMALE);
    }

    public void setIdentity(GlobalPos stoolPos, UUID settlementCoreId, UUID plotId, int tier, HireRequirement hireRequirement) {
        this.stoolPos = stoolPos;
        this.settlementCoreId = settlementCoreId;
        this.plotId = plotId;
        this.tier = tier;
        this.hireRequirement = hireRequirement;
        entityData.set(DATA_FEMALE, com.github.cerealklla.settlemynts.npc.NpcGender.roll() == com.github.cerealklla.settlemynts.npc.NpcGender.FEMALE);
    }

    public GlobalPos stoolPos() {
        return stoolPos;
    }

    public UUID settlementCoreId() {
        return settlementCoreId;
    }

    public UUID plotId() {
        return plotId;
    }

    public int tier() {
        return tier;
    }

    public HireRequirement hireRequirement() {
        return hireRequirement;
    }

    /** Baseline before {@code TavernStatRoller}'s own per-spawn ±20% variance overrides {@code MAX_HEALTH}/{@code ATTACK_DAMAGE} -- see that class's own doc. */
    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.ATTACK_DAMAGE, 2.0)
                .add(Attributes.MOVEMENT_SPEED, 0.25);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(1, new FloatGoal(this));
        // Priority 2 (melee/bow/spell) is deliberately NOT added here -- see reassessWeaponGoal().
        // Priority 4, NOT 3 -- real live-test bug, 2026-11-02: "used the spell but didn't melee while
        // the spell was on cooldown." reassessWeaponGoal()'s spell+melee-fallback branch adds
        // meleeGoal at priority 3 expecting the GoalSelector to fall through to it "for free" once
        // spellCastGoal's canUse() goes false -- but confirmed against the real decompiled vanilla
        // GoalSelector/WrappedGoal source that equal-priority-number goals can NEVER preempt each
        // other (canBeReplacedBy requires a STRICTLY lower number), and this goal, registered here in
        // the constructor before meleeGoal even exists, always won the shared Flag.MOVE claim first.
        // Priority 4 lets meleeGoal (priority 3) always legitimately preempt this one instead.
        this.goalSelector.addGoal(4, new CompanionFollowPlayerGoal(this, 0.8));
        // Real report, 2026-11-01: "the companion is spinning left and right while just riding in a
        // straight line." Not a vehicle/position-interpolation issue like Caryvan's wagon (that one's
        // a custom, non-living Entity that genuinely needed its own InterpolationHandler -- a real
        // vanilla Horse already has that built in, same as any mob). Root cause here is these two
        // goals: neither is gated on riding state, so both kept independently steering this entity's
        // own head/body rotation toward the owner (LookAtPlayerGoal) or a random idle direction
        // (RandomLookAroundGoal) while the horse was carrying it in a completely different direction
        // under its own travel AI -- two uncoordinated rotation sources fighting each other reads as
        // spinning. Neither vanilla class defers to {@code isPassenger()} on its own, and both are
        // plain, non-final {@code Goal}s, so each is subclassed here with exactly one line added
        // (`!isPassenger()` gating `canUse()`) rather than disabled outright -- they still work
        // normally for a companion standing on its own two feet, which was never the reported problem.
        this.goalSelector.addGoal(4, new LookAtPlayerGoal(this, Player.class, 8.0F) {
            @Override
            public boolean canUse() {
                return !isPassenger() && super.canUse();
            }
        });
        this.goalSelector.addGoal(5, new RandomLookAroundGoal(this) {
            @Override
            public boolean canUse() {
                return !isPassenger() && super.canUse();
            }
        });
        // A seated patron ("never get up") never actually moves even though these goals are always
        // registered -- CompanionFollowPlayerGoal/CompanionSightGoal/CompanionSpellCastGoal each
        // defer entirely while stoolPos is non-null, see this class's own doc.
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new CompanionSightGoal(this));
    }

    /** Direct copy of {@code guardhouse.GuardEntity#reassessWeaponGoal} -- see that method's own doc. Also defers entirely while seated (no weapon goal of any kind for a stool patron, matching "never get up"). */
    private void reassessWeaponGoal() {
        if (this.level() == null || this.level().isClientSide()) {
            return;
        }
        if (LyfeMagicianBridge.isLoaded()) {
            LyfeMagicianBridge.invalidateSpellTargetCache(this);
        }
        this.goalSelector.removeGoal(this.meleeGoal);
        this.goalSelector.removeGoal(this.bowGoal);
        this.goalSelector.removeGoal(this.spellCastGoal);
        if (stoolPos != null) {
            return; // seated patron -- never armed, regardless of mainhand contents
        }
        if (getMainHandItem().getItem() instanceof BowItem) {
            this.goalSelector.addGoal(2, this.bowGoal);
        } else if (LyfeMagicianBridge.isLoaded() && (LyfeMagicianBridge.isNatureStaff(getMainHandItem())
                || LyfeMagicianBridge.hasOffhandSpell(getOffhandItem()))) {
            this.goalSelector.addGoal(2, this.spellCastGoal);
            this.goalSelector.addGoal(3, this.meleeGoal);
        } else {
            this.goalSelector.addGoal(2, this.meleeGoal);
        }
    }

    @Override
    public void onEquipItem(EquipmentSlot slot, ItemStack oldStack, ItemStack stack) {
        super.onEquipItem(slot, oldStack, stack);
        if (slot == EquipmentSlot.MAINHAND || slot == EquipmentSlot.OFFHAND) {
            reassessWeaponGoal();
        }
    }

    /**
     * Real report, 2026-11-03: "my own companion killed me, that shouldn't be possible." Root cause:
     * this class had no owner-alliance concept at all -- {@link HurtByTargetGoal} (no predicate,
     * registered above) sets {@code this.mob.setTarget(...)} to whoever last damaged it with zero
     * ally filtering of its own; the actual filtering vanilla relies on happens one level up, inside
     * {@code TargetingConditions#test} (confirmed against the real decompiled source -- {@code
     * forCombat()}'s {@code targeter.isAlliedTo(target)} check), which only returns true if
     * {@link #considersEntityAsAlly} says so. {@code Entity#isAlliedTo} itself is {@code final} and
     * can't be overridden directly -- this protected hook is the real, intended extension point,
     * confirmed as exactly what vanilla's own {@code TamableAnimal#considersEntityAsAlly} overrides
     * for its owner. So an owner accidentally hitting their own companion (a sweep attack, a stray
     * arrow, AoE splash) used to make it retaliate for real -- now the owner is always an ally,
     * regardless of what damaged it last. This also protects {@code MeleeAttackGoal}/
     * {@code RangedBowAttackGoal}/{@code CompanionSpellCastGoal} for free, since all of them only ever
     * act on whatever target {@code HurtByTargetGoal}/{@code CompanionSightGoal} set in the first
     * place -- neither can ever set the owner as a target once this returns {@code true} for them.
     * {@code guardhouse.GuardEntity} has the identical unguarded {@code HurtByTargetGoal} but no
     * owner concept at all (a Guard retaliating against whoever hit it is the intended behavior
     * there), so this fix is deliberately scoped to the owned Companion case only.
     */
    @Override
    protected boolean considersEntityAsAlly(Entity other) {
        return LyfeSummoningBridge.resolveCompanionOwner(this)
                .map(ownerId -> ownerId.equals(other.getUUID()))
                .orElse(false)
                || super.considersEntityAsAlly(other);
    }

    /**
     * The entity that actually gets repositioned by pathing right now -- the companion's mount while
     * riding one, otherwise the companion itself. Originally a private method on {@link
     * CompanionFollowPlayerGoal} only; moved here, 2026-11-03, so {@link CompanionMeleeAttackGoal}/
     * {@link CompanionRangedAttackGoal} (new, same-day fix) can share the exact same resolution
     * instead of each re-deriving it. Root cause this exists at all: a mounted passenger's own {@code
     * Navigation}/{@code MoveControl} never actually repositions it -- vanilla only applies a mob's
     * own pathing to its real position while it's NOT a passenger ({@code Entity#rideTick} drives
     * position for a passenger instead, confirmed against the real decompiled source) -- so any goal
     * that calls {@code this.mob.getNavigation().moveTo(...)} unconditionally (every vanilla combat
     * goal does, confirmed by reading {@code MeleeAttackGoal}/{@code RangedBowAttackGoal}'s own real
     * decompiled source: neither has a clean override point, {@code mob.getNavigation()} is called
     * directly and repeatedly throughout both, not through one overridable hook) is a complete no-op
     * for a mounted companion. Real report, 2026-11-03: "when a companion riding a horse targets an
     * enemy they should move at mounted running speed to get to the target, not barely walking faster
     * than a player walks" -- whatever tiny motion was visible was purely the mount's own idle wander
     * AI, not real goal-directed movement toward the target.
     */
    public PathfinderMob mover() {
        return getVehicle() instanceof PathfinderMob mount ? mount : this;
    }

    /** Mirrors {@code guardhouse.GuardEntity#performRangedAttack} (itself mirroring {@code AbstractSkeleton}, confirmed against the real decompiled source). */
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
     * Real complaint, 2026-11-01: a Companion riding a horse sat a full 0.6 blocks too high, reading
     * as floating. Root-caused with real numbers via RCON (exact X/Z match with the horse, Y matching
     * the horse's own raw passenger-attachment height with zero correction applied), then confirmed
     * against the real decompiled {@code EntityType.java} source: {@code EntityType.PLAYER} is
     * registered with an explicit {@code .vehicleAttachment(Avatar.DEFAULT_VEHICLE_ATTACHMENT)} --
     * {@code Vec3(0, 0.6, 0)} -- which is what actually pulls a real player DOWN into a proper seated
     * position on anything it mounts; {@code Zombie}/{@code Husk}/{@code Vindicator} each carry their
     * own equivalent {@code .ridingOffset(...)}, but {@code Villager} (and this entity type, which
     * copies neither) has none at all, so it fell back to zero correction -- a real, measured gap
     * between how a player and a generic humanoid Mob sit on the exact same mount, not "that's just
     * how vanilla looks."
     *
     * <p>Deliberately overridden here (an instance method) rather than via {@code
     * ModEntities#TAVERN_NPC}'s own {@code EntityType.Builder#ridingOffset(...)}, which would apply
     * universally to ANY vehicle -- including the Tavern Stool's own non-living {@code
     * com.github.cerealklla.lyfe.rest.SeatEntity} mount point, separately tuned and already confirmed
     * working ("they look decent now"). Scoped to only correct for a real {@link LivingEntity} (a
     * horse or any other live mount), leaving stool-sitting completely untouched.
     */
    @Override
    public net.minecraft.world.phys.Vec3 getVehicleAttachmentPoint(net.minecraft.world.entity.Entity vehicle) {
        if (vehicle instanceof LivingEntity) {
            return new net.minecraft.world.phys.Vec3(0.0, 0.6, 0.0);
        }
        return super.getVehicleAttachmentPoint(vehicle);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.storeNullable("StoolPos", GlobalPos.CODEC, stoolPos);
        output.storeNullable("SettlementCoreId", UUIDUtil.CODEC, settlementCoreId);
        output.storeNullable("PlotId", UUIDUtil.CODEC, plotId);
        output.putInt("Tier", tier);
        HireRequirementCodec.save(output, hireRequirement);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        stoolPos = input.read("StoolPos", GlobalPos.CODEC).orElse(null);
        settlementCoreId = input.read("SettlementCoreId", UUIDUtil.CODEC).orElse(null);
        plotId = input.read("PlotId", UUIDUtil.CODEC).orElse(null);
        tier = input.getIntOr("Tier", 1);
        hireRequirement = HireRequirementCodec.load(input);
        // Same real bug GuardEntity#readAdditionalSaveData already fixed once (2026-10-08): super's
        // call above restores equipment via a raw EntityEquipment#setAll that bypasses onEquipItem
        // entirely, so a reloaded companion (every server restart) would otherwise stay stuck on
        // whatever goal the constructor picked. Called again now that stoolPos/equipment are both
        // genuinely populated from NBT.
        reassessWeaponGoal();
    }
}
