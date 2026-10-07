package com.github.cerealklla.settlemynts.zone;

import java.util.Map;
import java.util.UUID;

import com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.plotsign.PlotConfigSignBlockEntity;
import com.github.cerealklla.settlemynts.plotsign.PlotConfigSignIndex;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Drives {@link ShopSeeding#restockAtMidnight} once per real in-game day boundary (added 2026-10-05,
 * explicit user request) -- same day-boundary detection shape as Yconomics' own {@code
 * bills.BillProcessingTicker} (a local, duplicated two-line day-number calculation rather than a new
 * cross-mod dependency for something this small/pure), and the same whole-suite "scan every known
 * Plot Config Sign" trigger shape as {@code guardhouse.GuardSpawnTicker}/{@code
 * resident.ResidentSpawnTicker}, via {@link PlotConfigSignIndex} -- no world-wide entity scan needed.
 */
public final class ShopMidnightRestockTicker {

    private static final long TICKS_PER_DAY = 24000L;

    private long lastKnownDay = -1;

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        long currentDay = Math.floorDiv(server.overworld().getOverworldClockTime(), TICKS_PER_DAY);
        if (lastKnownDay < 0) {
            lastKnownDay = currentDay; // First tick since this listener was created -- establish the baseline only.
            return;
        }
        if (currentDay <= lastKnownDay) {
            return;
        }
        lastKnownDay = currentDay;

        if (!YconomicsShopBridge.isAvailable()) {
            return;
        }
        for (Map.Entry<UUID, GlobalPos> entry : PlotConfigSignIndex.get(server).all().entrySet()) {
            GlobalPos pos = entry.getValue();
            ServerLevel level = server.getLevel(pos.dimension());
            if (level == null) {
                continue;
            }
            restockPlot(level, entry.getKey(), pos.pos());
        }
    }

    private void restockPlot(ServerLevel level, UUID plotId, BlockPos signPos) {
        if (!(level.getBlockEntity(signPos) instanceof PlotConfigSignBlockEntity sign)
                || sign.settlementCoreId() == null
                || !(level.getEntity(sign.settlementCoreId()) instanceof GhostTownHallCoreEntity core)) {
            return; // Chunk not loaded, or the sign/core couldn't be resolved -- skip this day.
        }
        PlotRecord plot = core.getPlots().stream().filter(p -> p.plotId().equals(plotId)).findFirst().orElse(null);
        if (plot == null) {
            return;
        }
        ShopSeeding.restockAtMidnight(level, plot, signPos);
    }
}
