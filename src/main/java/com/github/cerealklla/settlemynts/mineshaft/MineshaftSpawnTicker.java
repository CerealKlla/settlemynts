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
        if (plot == null || (plot.owner().isPresent() && !plot.npcHired())
                || com.github.cerealklla.settlemynts.zone.PlotWorkerKind.resolve(plot) != com.github.cerealklla.settlemynts.zone.PlotWorkerKind.Kind.MINER) {
            return;
        }

        // Real, confirmed-live duplicate-worker bug fixed 2026-10-12 -- this used to also check
        // `level.getEntity(existing)` here and treat "not currently found" as proof of death, which
        // is only ever proof the worker's *current* chunk (it wanders, see MinerWorkGoal) isn't
        // loaded *right now* -- a near-guarantee right after a restart. The real worker would then
        // reload moments later alongside the brand-new one this spawned in its place. A slot is now
        // only ever cleared by a real death, via zone.PlotNpcDeathListener -- see its own doc for the
        // full root-cause trace. A non-null slot here always means "alive, don't touch."
        if (sign.minerWorkerId() != null) {
            return;
        }

        MinerWorkerEntity worker = new MinerWorkerEntity(ModEntities.MINER_WORKER.get(), level);
        worker.setPos(signPos.getX() + 0.5, signPos.getY(), signPos.getZ() + 0.5);
        worker.setPlotIdentity(sign.settlementCoreId(), plotId);
        level.addFreshEntity(worker);
        sign.setMinerWorkerId(worker.getUUID());
    }
}
