package com.github.cerealklla.settlemynts.lumberyard;

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
 * Fills a Lumberyard plot's worker slot (added 2026-10-05, explicit user request) -- exact same
 * presence-check trigger shape as {@code guardhouse.GuardSpawnTicker}/{@code
 * resident.ResidentSpawnTicker}: a cheap periodic scan over every known Plot Config Sign ({@link
 * PlotConfigSignIndex}, no world-wide entity scan), filling any Lumberyard plot whose slot is empty
 * or whose previous worker died. A killed worker's slot is naturally refilled on the next scan --
 * this single mechanism *is* the respawn, no separate timer needed.
 */
public final class LumberjackSpawnTicker {

    private static final int SCAN_INTERVAL_TICKS = 100; // 5 seconds, same cadence as the other spawn tickers.
    // Staggered 2026-10-12 against the other seven 100-tick full-world scan tickers across this
    // suite -- see FarmerSpawnTicker's own doc for the full "thundering herd" root cause.
    private static final int TICK_OFFSET = 25;

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
                || com.github.cerealklla.settlemynts.zone.PlotWorkerKind.resolve(plot) != com.github.cerealklla.settlemynts.zone.PlotWorkerKind.Kind.LUMBERJACK) {
            return;
        }

        // Real, confirmed-live duplicate-worker bug fixed 2026-10-12 (see
        // zone.PlotNpcDeathListener's own doc for the full root-cause trace, found via Mineshaft's
        // twin) -- this used to also check `level.getEntity(existing)` here and treat "not currently
        // found" as proof of death, which is only ever proof the worker's current chunk isn't loaded
        // *right now* (it wanders), near-guaranteed right after a restart. A slot is now only ever
        // cleared by a real death; a non-null slot here always means "alive, don't touch."
        if (sign.lumberjackWorkerId() != null) {
            return;
        }

        LumberjackWorkerEntity worker = new LumberjackWorkerEntity(ModEntities.LUMBERJACK_WORKER.get(), level);
        worker.setPos(signPos.getX() + 0.5, signPos.getY(), signPos.getZ() + 0.5);
        worker.setSpawnPos(signPos);
        worker.setPlotIdentity(sign.settlementCoreId(), plotId);
        level.addFreshEntity(worker);
        sign.setLumberjackWorkerId(worker.getUUID());
    }
}
