package com.github.cerealklla.settlemynts.mineshaft;

import java.util.Map;
import java.util.UUID;

import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.plotsign.PlotConfigSignBlockEntity;
import com.github.cerealklla.settlemynts.plotsign.PlotConfigSignIndex;
import com.github.cerealklla.settlemynts.registration.ModEntities;
import com.github.cerealklla.settlemynts.zone.PlotRecord;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Fills a Mineshaft plot's worker slot (added 2026-10-11, explicit user request) -- verbatim copy of
 * {@code lumberyard.LumberjackSpawnTicker}'s own shape, same presence-check trigger as every other
 * plot-worker spawn ticker in this suite: a cheap periodic scan over every known Plot Config Sign
 * ({@link PlotConfigSignIndex}, no world-wide entity scan), filling any Mineshaft plot whose slot is
 * empty or whose previous worker died. A killed worker's slot is naturally refilled on the next scan
 * -- this single mechanism *is* the respawn, no separate timer needed.
 */
public final class MineshaftSpawnTicker {

    private static final Identifier MINESHAFT_ZONE_TYPE_ID = Identifier.fromNamespaceAndPath("blueprynts", "mineshaft");
    private static final int SCAN_INTERVAL_TICKS = 100; // 5 seconds, same cadence as the other spawn tickers.
    // Staggered 2026-10-12 against the other seven 100-tick full-world scan tickers across this
    // suite -- see FarmerSpawnTicker's own doc for the full "thundering herd" root cause.
    private static final int TICK_OFFSET = 37;

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if ((server.getTickCount() + TICK_OFFSET) % SCAN_INTERVAL_TICKS != 0) {
            return;
        }
        for (Map.Entry<UUID, GlobalPos> entry : PlotConfigSignIndex.get(server).all().entrySet()) {
            GlobalPos pos = entry.getValue();
            ServerLevel level = server.getLevel(pos.dimension());
            if (level == null) {
                continue;
            }
            fillWorker(level, entry.getKey(), pos.pos());
        }
    }

    private void fillWorker(ServerLevel level, UUID plotId, BlockPos signPos) {
        if (!(level.getBlockEntity(signPos) instanceof PlotConfigSignBlockEntity sign)
                || sign.settlementCoreId() == null
                || !(level.getEntity(sign.settlementCoreId()) instanceof GhostTownHallCoreEntity core)) {
            return;
        }
        PlotRecord plot = core.getPlots().stream().filter(p -> p.plotId().equals(plotId)).findFirst().orElse(null);
        if (plot == null || plot.owner().isPresent() || !plot.zoneTypeId().equals(MINESHAFT_ZONE_TYPE_ID)) {
            return;
        }

        UUID existing = sign.minerWorkerId();
        if (existing != null) {
            net.minecraft.world.entity.Entity found = level.getEntity(existing);
            if (found instanceof MinerWorkerEntity living && living.isAlive()) {
                return; // Already filled.
            }
            // Temporary diagnostic (2026-10-12, real report: dozens of live miner_worker entities
            // piled at one plot) -- logs exactly why the "already filled" check failed to find the
            // existing worker, instead of guessing. Remove once the real cause is confirmed.
            com.github.cerealklla.settlemynts.SettlemyntsMod.LOGGER.info(
                    "[MinerSpawnDebug] plot={} sign existing minerWorkerId={} -- level.getEntity() returned {}",
                    plotId, existing, found == null ? "null" : (found.getClass().getSimpleName() + " isAlive=" + found.isAlive()));
            sign.setMinerWorkerId(null);
        }

        MinerWorkerEntity worker = new MinerWorkerEntity(ModEntities.MINER_WORKER.get(), level);
        worker.setPos(signPos.getX() + 0.5, signPos.getY(), signPos.getZ() + 0.5);
        worker.setPlotIdentity(sign.settlementCoreId(), plotId);
        level.addFreshEntity(worker);
        sign.setMinerWorkerId(worker.getUUID());
        com.github.cerealklla.settlemynts.SettlemyntsMod.LOGGER.info(
                "[MinerSpawnDebug] plot={} spawned new worker uuid={}", plotId, worker.getUUID());
    }
}
