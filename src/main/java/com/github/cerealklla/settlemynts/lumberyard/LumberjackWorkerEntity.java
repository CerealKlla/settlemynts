package com.github.cerealklla.settlemynts.lumberyard;

import java.util.Optional;
import java.util.UUID;

import com.github.cerealklla.cartographyr.api.Cartography;
import com.github.cerealklla.cartographyr.geo.EntityId;
import com.github.cerealklla.cartographyr.geo.GeographicEntity;
import com.github.cerealklla.cartographyr.geo.Geometry;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.zone.PlotRecord;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A Lumberyard plot's worker NPC (design doc Section 14a, added 2026-10-05, explicit user request:
 * "At a lumberyard an NPC should walk around within the plot planting Tree sapplings... and
 * automatically harvesting fully grown trees"). Same shape as {@code resident.ResidentVillagerEntity}
 * almost verbatim (plain profession-less {@code Villager}, Brain never ticked, patrols/works via a
 * plain {@code goalSelector} goal instead) -- the one real difference is {@link #resolvePatrolArea}
 * resolves the plot's *real* polygon ({@code PlotRecord#cartographyrPlotEntityId}), not the padded
 * "Town Proper" buffer {@code ResidentPatrolGoal} uses -- planting/harvesting must stay strictly
 * inside the plot itself, not its outer padding.
 */
public class LumberjackWorkerEntity extends Villager implements com.github.cerealklla.settlemynts.zone.PlotNpc {

    private static final int UNDERGROUND_TELEPORT_THRESHOLD_BLOCKS = 5;

    private UUID settlementCoreId;
    private UUID plotId;

    public LumberjackWorkerEntity(EntityType<? extends Villager> type, Level level) {
        super(type, level);
        skipDropExperience();
    }

    /** Called once by {@link LumberjackSpawnTicker} right after construction. */
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

    /** Same resolution chain as {@code resident.ResidentVillagerEntity#resolvePatrolArea}, but the real plot polygon, not the buffer. */
    public Optional<Geometry.Polygon> resolvePatrolArea(ServerLevel level) {
        return resolvePlotRecord(level).flatMap(plot ->
                Cartography.getEntity(level, new EntityId(plot.cartographyrPlotEntityId()))
                        .map(GeographicEntity::geometry)
                        .filter(Geometry.Polygon.class::isInstance)
                        .map(Geometry.Polygon.class::cast));
    }

    /**
     * Whether this plot's own Zone Type genuinely is Lumberyard, as opposed to a different Zone Type
     * (e.g. Private Residence) that merely hired this worker via "Change Job" -- added 2026-10-16,
     * explicit user spec: "private residence farmers and lumberjacks are only supposed to harvest and
     * re-plant existing plants, not plant wholly new ones." {@link LumberjackWorkGoal#findTarget} only
     * looks for a brand-new planting spot when this is {@code true}; a hired Lumberjack on any other
     * Zone Type only ever harvests whole real trees (the one spot a sapling can then regrow at).
     */
    public boolean isNativeLumberyardZone(ServerLevel level) {
        return resolvePlotRecord(level)
                .map(plot -> plot.zoneTypeId().equals(com.github.cerealklla.settlemynts.zone.PlotWorkerKind.LUMBERYARD_ZONE_TYPE_ID))
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
        this.goalSelector.addGoal(2, new LumberjackWorkGoal(this, 0.4));
        this.goalSelector.addGoal(3, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(4, new RandomLookAroundGoal(this));
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        // Deliberately never calls super.customServerAiStep (Villager's own) -- see class doc.
        int blockX = Mth.floor(getX());
        int blockZ = Mth.floor(getZ());
        if (isActuallyTrappedUnderground(level, blockX, blockZ)) {
            getNavigation().stop();
            int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ);
            teleportTo(blockX + 0.5, surfaceY, blockZ + 0.5);
        }
    }

    /**
     * Real fix, 2026-10-11, for a live report: "Lumberjack is still climbing trees instead of
     * chopping down the tree." The old check compared a heightmap column's surface against the
     * worker's own Y -- but {@code MOTION_BLOCKING_NO_LEAVES} counts logs as solid, so a worker
     * simply standing next to (or approaching) a trunk has its own column's "surface" read as the
     * top of the tree, not real ground, and got teleported straight up onto it. That's exactly what
     * looked like "climbing" -- a repeated false-positive escape teleport, not a pathfinding issue.
     * This was already flagged once as a known edge case (see {@code LumberjackWorkGoal}'s
     * {@code HARVEST_ARRIVE_DIST_SQ} history) and worked around there, but never actually fixed here
     * at the source.
     *
     * <p>Fixed by checking whether the worker is genuinely enclosed -- walking straight up from its
     * own position looking for real open space, ignoring logs/leaves entirely (a tree overhead is
     * not "underground"). Only a solid, non-tree ceiling across the whole threshold band counts as
     * actually trapped.
     */
    private boolean isActuallyTrappedUnderground(ServerLevel level, int blockX, int blockZ) {
        int startY = Mth.floor(getY()) + 1;
        for (int dy = 0; dy < UNDERGROUND_TELEPORT_THRESHOLD_BLOCKS; dy++) {
            BlockPos pos = new BlockPos(blockX, startY + dy, blockZ);
            BlockState state = level.getBlockState(pos);
            if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)) {
                continue; // Tree material overhead -- not a real ceiling, keep scanning upward.
            }
            if (state.getCollisionShape(level, pos).isEmpty()) {
                return false; // Found real open space within the threshold -- not trapped.
            }
        }
        return true;
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
    }
}
