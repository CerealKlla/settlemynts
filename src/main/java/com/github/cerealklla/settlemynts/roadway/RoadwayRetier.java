package com.github.cerealklla.settlemynts.roadway;

import java.util.UUID;

import com.github.cerealklla.settlemynts.SettlemyntsMod;
import com.github.cerealklla.settlemynts.registration.ModBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Re-tiers every already-paved Roadway block for one settlement (added 2026-10-10, replacing the
 * former {@code RoadwayTierTicker} periodic poll -- explicit user correction: "there should be no
 * ticker for this at all. A player manually upgrades the PLOT tier... and that should automatically
 * improve all the roads." Event-triggered now, called directly from {@code SettlemyntsMod}'s
 * "Upgrade Plot" handler right after a Town Hall plot's own Tier is raised -- the only event that
 * can ever actually change {@link RoadwayTierResolver#currentTier}, so there is nothing left to poll
 * for. Same "only a repaint, never a re-pave" behavior the old ticker had.
 *
 * <p>Only re-tiers connections that already have a non-empty {@link
 * RoadwayStakeEntity.ConnectionSnapshot#roadCells()} -- a connection paved before that field existed
 * has no recorded cell list and is simply left at whatever it already looked like until it's
 * re-paved (removed and reconnected), a deliberate, accepted gap (see decisions.md), not a
 * migration.
 */
public final class RoadwayRetier {

    private RoadwayRetier() {
    }

    public static void repaintSettlementRoads(ServerLevel level, UUID settlementCoreId, int tier) {
        BlockState roadState = ModBlocks.ROADWAY.get().defaultBlockState().setValue(TieredRoadwayBlock.TIER, tier);
        int cellsRepainted = 0;
        for (RoadwayStakeEntity stake : RoadwayStakeEntity.findByOwnerCore(level, settlementCoreId)) {
            for (RoadwayStakeEntity.ConnectionSnapshot connection : stake.getConnectionSnapshots()) {
                // Authoritative path (2026-10-18, see RoadwayPaver.StepRecord's own doc for the real
                // bug this fixes) -- a step cell's own recorded climb direction, independent of
                // whatever block is actually sitting there right now. This is the only way to
                // correctly turn a flat Tier-1/2 riser cell into a real stair the first time a
                // settlement crosses the Tier-3 threshold, since there was never a stairs block there
                // to read a FACING off at all.
                java.util.Set<BlockPos> stepPositions = new java.util.HashSet<>();
                for (RoadwayPaver.StepRecord step : connection.stepCells()) {
                    stepPositions.add(step.pos());
                    BlockState newStairState = RoadwayPaver.stairStateFor(tier, step.climbDirection());
                    level.setBlock(step.pos(), newStairState != null ? newStairState : roadState, 3);
                    cellsRepainted++;
                }
                for (BlockPos pos : connection.roadCells()) {
                    if (stepPositions.contains(pos)) {
                        continue; // Already handled above via its recorded StepRecord.
                    }
                    BlockState existing = level.getBlockState(pos);
                    if (existing.is(ModBlocks.ROADWAY.get())) {
                        level.setBlock(pos, roadState, 3);
                        cellsRepainted++;
                    } else if (existing.is(Blocks.STONE_BRICK_STAIRS) || existing.is(Blocks.BRICK_STAIRS) || existing.is(Blocks.SMOOTH_QUARTZ_STAIRS)) {
                        // Backward-compat path for a connection paved before stepCells existed
                        // (2026-10-12 through 2026-10-17) -- its own step cells already physically
                        // became a real stairs block at pave time (every tier that bug actually hit,
                        // 3/4/5, has a real stairs match), so re-selecting the new tier's own stairs
                        // match off the existing block's FACING (keeping climb direction/half) still
                        // works correctly here, just never for a step that was flat to begin with --
                        // see stepCells' own doc for exactly which case this doesn't cover.
                        BlockState newStairState = RoadwayPaver.stairStateFor(tier, existing.getValue(StairBlock.FACING));
                        level.setBlock(pos, newStairState != null ? newStairState.setValue(StairBlock.HALF, existing.getValue(StairBlock.HALF)) : roadState, 3);
                        cellsRepainted++;
                    }
                }
            }
        }
        SettlemyntsMod.LOGGER.info(
                "[Roadway] Settlement {} -- Town Hall Tier now {}, re-tiered {} road cell(s)",
                settlementCoreId, tier, cellsRepainted);
    }
}
