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
                for (BlockPos pos : connection.roadCells()) {
                    BlockState existing = level.getBlockState(pos);
                    if (existing.is(ModBlocks.ROADWAY.get())) {
                        level.setBlock(pos, roadState, 3);
                        cellsRepainted++;
                    } else if (existing.is(Blocks.STONE_BRICK_STAIRS) || existing.is(Blocks.BRICK_STAIRS)) {
                        // Slope-step stair cell (2026-10-12) -- not the flat ROADWAY block, so the
                        // check above misses it; re-select the new tier's own stairs match (keeping
                        // the existing climb direction/half), falling back to the flat road if the
                        // new tier has no real stairs match (Tiers 1/2/5 -- see
                        // RoadwayPaver#stairStateFor's own doc).
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
