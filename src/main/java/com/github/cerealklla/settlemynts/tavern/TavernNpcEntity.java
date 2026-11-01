package com.github.cerealklla.settlemynts.tavern;

import java.util.UUID;

import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A seated Tavern NPC -- design doc Section 17: "targeted spawn locations for new NPCs, when they
 * spawn they will be seated on the stools and never get up." Same base class/no-Brain shape as
 * {@code lumberyard.LumberjackWorkerEntity}/{@code resident.ResidentVillagerEntity} (a plain,
 * profession-less {@code Villager} -- real vanilla hitbox/rendering via {@code VillagerRenderer} for
 * free, Brain never ticked, behavior driven by a plain {@code goalSelector} instead) -- but unlike
 * those, "never get up" means registering NO movement goal at all, not even a work goal. {@link
 * #hireRequirement} and {@link #tier} are rolled once at spawn ({@code TavernDailySpawnTicker}); the
 * NPC's own current equipment/stats (set by {@code TavernGearRoller}/{@code TavernStatRoller} at
 * that same moment) are what actually get captured into the eventual Companion Token -- nothing
 * extra needs saving here for that part.
 *
 * <p><b>Reuses Lyfe's real stool furniture</b> (2026-10-31 correction, after a first pass that
 * floated a placeholder {@code Blocks.OAK_SLAB} display instead of this suite's already-existing
 * sit-able stool block) -- {@code TavernDailySpawnTicker} mounts a freshly spawned NPC onto the same
 * {@code com.github.cerealklla.lyfe.rest.SeatEntity} mount point a player sitting on that same stool
 * would use (via {@code bridge.LyfeTavernBridge#seatOnStool}), rather than just standing at the
 * stool's position. <b>Known v1 simplification</b>: {@code VillagerRenderer} has no dedicated
 * sit-pose, so the mounted NPC still renders upright (just offset to seat height) -- same
 * flagged-placeholder footing as every other "functional first" visual cut in this mod (e.g. {@code
 * guardhouse.GuardRenderer}'s reskinned-Zombie model).
 */
public class TavernNpcEntity extends Villager {

    private GlobalPos stoolPos;
    private UUID settlementCoreId;
    private UUID plotId;
    private int tier = 1;
    private HireRequirement hireRequirement;

    public TavernNpcEntity(EntityType<? extends Villager> type, Level level) {
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

    public static AttributeSupplier.Builder createAttributes() {
        return Villager.createAttributes();
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(1, new FloatGoal(this));
        this.goalSelector.addGoal(2, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(3, new RandomLookAroundGoal(this));
        // Deliberately no movement/work goal of any kind -- "never get up."
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        // Deliberately never calls super.customServerAiStep (Villager's own Brain tick) -- same
        // "plain Villager, Brain never ticked" shape as every other worker NPC in this mod.
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
