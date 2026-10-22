package com.github.cerealklla.settlemynts.farm;

import java.util.Optional;
import java.util.UUID;

import com.github.cerealklla.cartographyr.api.Cartography;
import com.github.cerealklla.cartographyr.geo.EntityId;
import com.github.cerealklla.cartographyr.geo.GeographicEntity;
import com.github.cerealklla.cartographyr.geo.Geometry;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.zone.PlotRecord;

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
 * A Farm plot's worker NPC (design doc Section 14a, added 2026-10-05, explicit user request: "a Farm
 * plot's NPC should walk around tilling and planting on soil... Harvesting should move the items into
 * boxes within the plot"). Same shape as {@code lumberyard.LumberjackWorkerEntity} almost verbatim --
 * see that class's own doc for why this mirrors {@code resident.ResidentVillagerEntity} but patrols/
 * works within the plot's *real* polygon, not its padded buffer.
 */
public class FarmerWorkerEntity extends Villager implements com.github.cerealklla.settlemynts.zone.PlotNpc, com.github.cerealklla.nuclyus.statbar.StatBarOwner {

    private final com.github.cerealklla.settlemynts.npc.NpcStuckWatchdog stuckWatchdog = new com.github.cerealklla.settlemynts.npc.NpcStuckWatchdog();

    private UUID settlementCoreId;
    private UUID plotId;
    private net.minecraft.core.BlockPos spawnPos;

    public FarmerWorkerEntity(EntityType<? extends Villager> type, Level level) {
        super(type, level);
        skipDropExperience();
        // Real report, 2026-10-17: "NPCs keep getting stuck up in trees" -- see
        // guardhouse.GuardEntity's own identical fix for the full writeup.
        this.setPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.LEAVES, -1.0F);
    }

    /** Called once by {@link FarmerSpawnTicker} right after construction. */
    public void setPlotIdentity(UUID settlementCoreId, UUID plotId) {
        this.settlementCoreId = settlementCoreId;
        this.plotId = plotId;
    }

    @Override
    public UUID plotId() {
        return plotId;
    }

    @Override
    public UUID settlementCoreId() {
        return settlementCoreId;
    }

    /** Called once by {@link FarmerSpawnTicker} right after construction -- this worker's own spawn point (its Plot Config Sign's position), the rescue destination {@link #stuckWatchdog} teleports back to if this worker ever gets stuck. */
    public void setSpawnPos(net.minecraft.core.BlockPos spawnPos) {
        this.spawnPos = spawnPos;
    }

    public Optional<Geometry.Polygon> resolvePatrolArea(ServerLevel level) {
        return resolvePlotRecord(level).flatMap(plot ->
                Cartography.getEntity(level, new EntityId(plot.cartographyrPlotEntityId()))
                        .map(GeographicEntity::geometry)
                        .filter(Geometry.Polygon.class::isInstance)
                        .map(Geometry.Polygon.class::cast));
    }

    /**
     * Whether this plot's own Zone Type genuinely is Farm, as opposed to a different Zone Type (e.g.
     * Private Residence) that merely hired this worker via "Change Job" -- added 2026-10-16, explicit
     * user spec: "private residence farmers and lumberjacks are only supposed to harvest and re-plant
     * existing plants, not plant wholly new ones." {@link FarmerWorkGoal#findTarget} only looks for a
     * brand-new planting spot when this is {@code true}; a hired Farmer on any other Zone Type only
     * ever harvests (which already replants at the exact same spot).
     */
    public boolean isNativeFarmZone(ServerLevel level) {
        return resolvePlotRecord(level)
                .map(plot -> plot.zoneTypeId().equals(com.github.cerealklla.settlemynts.zone.PlotWorkerKind.FARM_ZONE_TYPE_ID))
                .orElse(false);
    }

    private Optional<PlotRecord> resolvePlotRecord(ServerLevel level) {
        if (settlementCoreId == null || plotId == null) {
            return Optional.empty();
        }
        if (!(level.getEntity(settlementCoreId) instanceof GhostTownHallCoreEntity core)) {
            return Optional.empty();
        }
        return core.getPlots().stream().filter(p -> p.plotId().equals(plotId)).findFirst();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Villager.createAttributes();
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(1, new FloatGoal(this));
        this.goalSelector.addGoal(2, new FarmerWorkGoal(this, 0.4));
        this.goalSelector.addGoal(3, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(4, new RandomLookAroundGoal(this));
    }

    /** See {@code guardhouse.GuardEntity#customServerAiStep}'s own doc -- the old heightmap-scan-based rescue is gone; this just hands off to the shared {@link com.github.cerealklla.settlemynts.npc.NpcStuckWatchdog}. */
    @Override
    protected void customServerAiStep(ServerLevel level) {
        stuckWatchdog.tick(this, spawnPos);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.storeNullable("SettlementCoreId", UUIDUtil.CODEC, settlementCoreId);
        output.storeNullable("PlotId", UUIDUtil.CODEC, plotId);
        output.storeNullable("SpawnPos", net.minecraft.core.BlockPos.CODEC, spawnPos);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        settlementCoreId = input.read("SettlementCoreId", UUIDUtil.CODEC).orElse(null);
        plotId = input.read("PlotId", UUIDUtil.CODEC).orElse(null);
        spawnPos = input.read("SpawnPos", net.minecraft.core.BlockPos.CODEC).orElse(null);
    }
}
