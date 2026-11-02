package com.github.cerealklla.settlemynts.tavern;

import java.util.UUID;

import com.github.cerealklla.settlemynts.bridge.LyfeMagicianBridge;

import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
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
public class TavernNpcEntity extends PathfinderMob implements RangedAttackMob {

    private final RangedBowAttackGoal<TavernNpcEntity> bowGoal = new RangedBowAttackGoal<>(this, 1.0, 20, 15.0F);
    private final MeleeAttackGoal meleeGoal = new MeleeAttackGoal(this, 1.0, false);
    private final CompanionSpellCastGoal spellCastGoal = new CompanionSpellCastGoal(this);

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

    public void setIdentity(GlobalPos stoolPos, UUID settlementCoreId, UUID plotId, int tier, HireRequirement hireRequirement) {
        this.stoolPos = stoolPos;
        this.settlementCoreId = settlementCoreId;
        this.plotId = plotId;
        this.tier = tier;
        this.hireRequirement = hireRequirement;
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
        this.goalSelector.addGoal(3, new CompanionFollowPlayerGoal(this, 0.8));
        this.goalSelector.addGoal(4, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(5, new RandomLookAroundGoal(this));
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
