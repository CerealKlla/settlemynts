package com.github.cerealklla.settlemynts.roadway;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.github.cerealklla.settlemynts.SettlemyntsMod;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.plotsign.PlotConfigSignBlockEntity;
import com.github.cerealklla.settlemynts.plotsign.PlotConfigSignIndex;
import com.github.cerealklla.settlemynts.registration.ModBlocks;
import com.github.cerealklla.settlemynts.zone.PlotRecord;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Re-tiers every already-paved Roadway block whenever a settlement's Town Hall Tier changes (added
 * 2026-10-09, explicit request: "when the Town Hall is upgraded all the roads will upgrade with
 * it" -- see {@link RoadwayTierResolver}'s own doc for the Tier-resolution rules). Periodic poll,
 * same shape as {@code zone.ShopMidnightRestockTicker}/{@code resident.ResidentSpawnTicker} --
 * scans every known Town Hall plot (via {@link PlotConfigSignIndex}, not a world-wide block scan)
 * every {@link #SCAN_INTERVAL_TICKS}, and only touches any blocks when the resolved tier actually
 * differs from what was last seen for that settlement (cached by core UUID) -- a steady-state
 * settlement costs nothing beyond the cheap tier lookup itself.
 *
 * <p>Only re-tiers connections that already have a non-empty {@link
 * RoadwayStakeEntity.ConnectionSnapshot#roadCells()} -- a connection paved before this feature
 * existed has no recorded cell list and is simply left at whatever it already looked like until
 * it's re-paved (removed and reconnected), a deliberate, accepted gap (see decisions.md), not a
 * migration.
 */
public final class RoadwayTierTicker {

    private static final int SCAN_INTERVAL_TICKS = 100; // 5 seconds, same cadence as the other periodic settlement scans.

    private final Map<UUID, Integer> lastKnownTierByCore = new HashMap<>();

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % SCAN_INTERVAL_TICKS != 0) {
            return;
        }
        for (Map.Entry<UUID, GlobalPos> entry : PlotConfigSignIndex.get(server).all().entrySet()) {
            GlobalPos pos = entry.getValue();
            ServerLevel level = server.getLevel(pos.dimension());
            if (level == null) {
                continue;
            }
            checkPlot(level, entry.getKey(), pos.pos());
        }
    }

    private void checkPlot(ServerLevel level, UUID plotId, BlockPos signPos) {
        if (!(level.getBlockEntity(signPos) instanceof PlotConfigSignBlockEntity sign)
                || sign.settlementCoreId() == null
                || !(level.getEntity(sign.settlementCoreId()) instanceof GhostTownHallCoreEntity core)) {
            return;
        }
        PlotRecord plot = core.getPlots().stream().filter(p -> p.plotId().equals(plotId)).findFirst().orElse(null);
        if (plot == null || !GhostTownHallCoreEntity.TOWN_HALL_ZONE_TYPE_ID.equals(plot.zoneTypeId())) {
            return; // Only the Town Hall plot itself drives this -- every other plot's sign is irrelevant here.
        }
        int tier = RoadwayTierResolver.currentTier(level, core);
        Integer lastKnown = lastKnownTierByCore.put(core.getUUID(), tier);
        if (lastKnown != null && lastKnown == tier) {
            return;
        }
        repaintRoads(level, core.getUUID(), tier);
    }

    private void repaintRoads(ServerLevel level, UUID settlementCoreId, int tier) {
        BlockState roadState = ModBlocks.ROADWAY.get().defaultBlockState().setValue(TieredRoadwayBlock.TIER, tier);
        int cellsRepainted = 0;
        for (RoadwayStakeEntity stake : RoadwayStakeEntity.findByOwnerCore(level, settlementCoreId)) {
            for (RoadwayStakeEntity.ConnectionSnapshot connection : stake.getConnectionSnapshots()) {
                for (BlockPos pos : connection.roadCells()) {
                    if (level.getBlockState(pos).is(ModBlocks.ROADWAY.get())) {
                        level.setBlock(pos, roadState, 3);
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
