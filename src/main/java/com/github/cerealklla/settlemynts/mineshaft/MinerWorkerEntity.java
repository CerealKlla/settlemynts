package com.github.cerealklla.settlemynts.mineshaft;

import java.util.Optional;
import java.util.UUID;

import com.github.cerealklla.cartographyr.api.Cartography;
import com.github.cerealklla.cartographyr.geo.EntityId;
import com.github.cerealklla.cartographyr.geo.GeographicEntity;
import com.github.cerealklla.cartographyr.geo.Geometry;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.zone.PlotNpc;
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
 * A Mineshaft plot's worker NPC (added 2026-10-11, explicit user request) -- same shape as {@code
 * lumberyard.LumberjackWorkerEntity} (plain profession-less {@code Villager}, Brain never ticked,
 * works via a plain {@code goalSelector} goal instead). Used to deliberately skip the suite's old
 * heightmap-scan-based underground-escape safety net entirely -- this entity's two waypoints
 * ({@code MinerWorkGoal}'s mine entrance and chest) are explicit fixed targets, and the entrance
 * waypoint legitimately sits below ground (a Mineshaft's own Tier footprint extends down to -8
 * blocks), so a Y-position-based "am I underground?" check would misfire constantly against this
 * entity's own intended design. The 2026-10-22 {@link
 * com.github.cerealklla.settlemynts.npc.NpcStuckWatchdog} replacement doesn't have that problem at
 * all -- it never looks at Y position or block state, only whether the worker is actually making
 * progress toward wherever it's currently navigating -- so this entity now gets the same generic
 * stuck-rescue every other ground NPC does, safely.
 */
public class MinerWorkerEntity extends Villager implements PlotNpc, com.github.cerealklla.nuclyus.statbar.StatBarOwner {

    private final com.github.cerealklla.settlemynts.npc.NpcStuckWatchdog stuckWatchdog = new com.github.cerealklla.settlemynts.npc.NpcStuckWatchdog();

    private UUID settlementCoreId;
    private UUID plotId;
    private net.minecraft.core.BlockPos spawnPos;

    public MinerWorkerEntity(EntityType<? extends Villager> type, Level level) {
        super(type, level);
        skipDropExperience();
        // Real report, 2026-10-17: "NPCs keep getting stuck up in trees" -- see
        // guardhouse.GuardEntity's own identical fix for the full writeup.
        this.setPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.LEAVES, -1.0F);
    }

    /** Called once by {@link MineshaftSpawnTicker} right after construction. */
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

    /** Called once by {@link MineshaftSpawnTicker} right after construction -- this worker's own spawn point (its Plot Config Sign's position), the rescue destination {@link #stuckWatchdog} teleports back to if this worker ever gets stuck. */
    public void setSpawnPos(net.minecraft.core.BlockPos spawnPos) {
        this.spawnPos = spawnPos;
    }

    /** Same resolution chain as {@code lumberyard.LumberjackWorkerEntity#resolvePatrolArea}. */
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
        return Cartography.getEntity(level, new EntityId(plot.cartographyrPlotEntityId()))
                .map(GeographicEntity::geometry)
                .filter(Geometry.Polygon.class::isInstance)
                .map(Geometry.Polygon.class::cast);
    }

    /** The owning plot's current {@link PlotRecord}, if resolvable -- used by {@link MinerWorkGoal} for tier/box lookups. */
    public Optional<PlotRecord> resolvePlotRecord(ServerLevel level) {
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
        this.goalSelector.addGoal(2, new MinerWorkGoal(this, 0.4));
        this.goalSelector.addGoal(3, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(4, new RandomLookAroundGoal(this));
    }

    /**
     * Real bug found via live testing, 2026-10-11: this override was missing entirely (only the
     * underground-escape logic it used to carry, inherited from {@code LumberjackWorkerEntity}, was
     * deliberately dropped -- see this class's own doc -- but that silently dropped the override
     * itself too). Without it, vanilla {@code Villager}'s own Brain keeps ticking and drives its own
     * wander/socialize/job-seek behavior every tick, fighting {@link MinerWorkGoal} for control of
     * movement -- looked exactly like "the NPC just wanders away like it isn't supposed to be going
     * anywhere," confirmed live on a flat world with no terrain complexity to blame instead.
     */
    @Override
    protected void customServerAiStep(ServerLevel level) {
        // Deliberately never calls super.customServerAiStep (Villager's own) -- see class/method doc.
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
