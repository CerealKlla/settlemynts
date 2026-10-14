package com.github.cerealklla.settlemynts.roadway;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.github.cerealklla.settlemynts.SettlemyntsMod;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.registration.ModBlocks;
import com.github.cerealklla.settlemynts.zone.PlotRecord;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Re-tiers every already-paved Highway (inter-settlement road) connected to one settlement, mirroring
 * {@link RoadwayRetier} -- added 2026-10-13, explicit user spec: "the trigger should only fire when a
 * plot tier increases. Recalculate its settlement's average plot tier, and then average that with the
 * connected settlement. If the average increases, then increase the tier of the connecting road to
 * match." Called directly from {@code SettlemyntsMod}'s "Upgrade Plot" handler for ANY plot (not just
 * Town Hall -- unlike {@link RoadwayRetier}, which only cares about the Town Hall's own Tier via
 * {@link RoadwayTierResolver}, a Highway's Tier is the average of a settlement's own {@link
 * SettlementTierResolver}, which already weighs every plot equally).
 *
 * <p>Deliberately reads from {@link HighwayStakeGraph} (persisted, survives a completed Highway's own
 * ghost stakes being hidden) rather than live {@link HighwayStakeEntity} instances, unlike {@link
 * RoadwayRetier} (which can get away with a live scan since Roadway stakes are never discarded after
 * connecting). A Highway's stakes ARE discarded once the road completes (see {@link
 * HighwayStakeEntity#hideAllForInstance}), so a live-entity scan would find nothing for any Highway
 * that's actually finished -- exactly the case this feature cares about most.
 */
public final class HighwayRetier {

    private HighwayRetier() {
    }

    /**
     * Re-tiers every Highway connected to {@code settlementCoreId}, if that settlement's own just-
     * recomputed average plot Tier raises the road's target Tier above what it's currently paved at.
     * {@code newOwnTier} is passed in rather than recomputed here -- the caller (the "Upgrade Plot"
     * handler) already has the live, just-updated plot list in hand and there's no reason to re-derive
     * it from scratch.
     */
    public static void retierHighwaysForSettlement(ServerLevel level, UUID settlementCoreId, int newOwnTier) {
        HighwayStakeGraph graph = HighwayStakeGraph.get(level.getServer());
        for (UUID instanceId : graph.instancesForSettlement(settlementCoreId)) {
            Optional<HighwayStakeGraph.InstanceMeta> metaOpt = graph.instanceMeta(instanceId);
            if (metaOpt.isEmpty()) {
                continue;
            }
            HighwayStakeGraph.InstanceMeta meta = metaOpt.get();
            UUID otherCoreId = settlementCoreId.equals(meta.settlementCoreIdA().orElse(null))
                    ? meta.settlementCoreIdB().orElse(null) : meta.settlementCoreIdA().orElse(null);
            int otherTier = otherSettlementTier(level, otherCoreId);
            int newRoadTier = Math.max(1, Math.round((newOwnTier + otherTier) / 2.0f));
            if (newRoadTier <= meta.tier()) {
                continue; // Only ever upgrades -- explicit user spec ("if the average increases").
            }
            repaintInstance(level, graph, instanceId, newRoadTier);
            graph.updateInstanceTier(instanceId, newRoadTier);
            SettlemyntsMod.LOGGER.info("[Highway] Instance {} -- settlement {} Tier now {}, road re-tiered to {}",
                    instanceId, settlementCoreId, newOwnTier, newRoadTier);
        }
    }

    /** {@code 1} if the other side has no resolvable core (a natural village, or simply not currently loaded) -- same fallback {@link SettlementTierResolver#averageTier} already applies to an empty plot list. */
    private static int otherSettlementTier(ServerLevel level, UUID otherCoreId) {
        if (otherCoreId == null) {
            return 1;
        }
        if (level.getEntity(otherCoreId) instanceof GhostTownHallCoreEntity otherCore) {
            return SettlementTierResolver.averageTier(otherCore.getPlots());
        }
        return SettlementTierResolver.averageTier(List.<PlotRecord>of());
    }

    private static void repaintInstance(ServerLevel level, HighwayStakeGraph graph, UUID instanceId, int tier) {
        BlockState roadState = ModBlocks.ROADWAY.get().defaultBlockState().setValue(TieredRoadwayBlock.TIER, tier);
        int cellsRepainted = 0;
        for (BlockPos pos : graph.roadCellsForInstance(instanceId)) {
            BlockState existing = level.getBlockState(pos);
            if (existing.is(ModBlocks.ROADWAY.get())) {
                level.setBlock(pos, roadState, 3);
                cellsRepainted++;
            } else if (existing.is(Blocks.STONE_BRICK_STAIRS) || existing.is(Blocks.BRICK_STAIRS)) {
                // Slope-step stair cell -- re-select the new Tier's own stairs match (keeping the
                // existing climb direction/half), falling back to the flat road if the new Tier has
                // no real stairs match (Tiers 1/2/5 -- see HighwayPaver#stairStateFor's own doc).
                BlockState newStairState = HighwayPaver.stairStateFor(tier, existing.getValue(StairBlock.FACING));
                level.setBlock(pos, newStairState != null ? newStairState.setValue(StairBlock.HALF, existing.getValue(StairBlock.HALF)) : roadState, 3);
                cellsRepainted++;
            }
        }
        SettlemyntsMod.LOGGER.info("[Highway] Instance {} -- re-tiered {} road cell(s) to Tier {}", instanceId, cellsRepainted, tier);
    }
}
