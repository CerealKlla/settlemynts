package com.github.cerealklla.settlemynts.tavern;

import java.util.UUID;

import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A seated Tavern NPC -- design doc Section 17: "targeted spawn locations for new NPCs, when they
 * spawn they will be seated on the stools and never get up." "Never get up" means registering NO
 * movement goal at all, not even a work goal. {@link #hireRequirement} and {@link #tier} are rolled
 * once at spawn ({@code TavernDailySpawnTicker}); the NPC's own current equipment/stats (set by
 * {@code TavernGearRoller}/{@code TavernStatRoller} at that same moment) are what actually get
 * captured into the eventual Companion Token -- nothing extra needs saving here for that part.
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
 */
public class TavernNpcEntity extends PathfinderMob {

    private GlobalPos stoolPos;
    private UUID settlementCoreId;
    private UUID plotId;
    private int tier = 1;
    private HireRequirement hireRequirement;

    public TavernNpcEntity(EntityType<? extends TavernNpcEntity> type, Level level) {
        super(type, level);
        skipDropExperience();
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
        this.goalSelector.addGoal(2, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(3, new RandomLookAroundGoal(this));
        // Deliberately no movement/work goal of any kind -- "never get up."
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
    }
}
