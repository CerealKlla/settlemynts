package com.github.cerealklla.settlemynts.zone;

import java.util.Map;
import java.util.UUID;

import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.plotsign.PlotConfigSignBlockEntity;
import com.github.cerealklla.settlemynts.plotsign.PlotConfigSignIndex;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Charges every plot with {@link PlotRecord#npcHired()} set {@link NpcHireMaintenance#costFor} once
 * per real in-game day -- design doc Section 14a, explicit user request, 2026-10-15. Same
 * day-boundary detection shape as {@code zone.ShopMidnightRestockTicker}, and the same "scan every
 * known Plot Config Sign" trigger ({@link PlotConfigSignIndex}) the spawn tickers already use.
 *
 * <p>On a successful charge, just stamps {@link PlotRecord#npcLastChargedDay} -- the worker itself
 * is left to the existing spawn tickers ({@code resident.ResidentSpawnTicker}/etc.), which already
 * fill any eligible plot's empty slot within their own ~5-second scan; {@code npcHired} alone is
 * what makes a player-owned plot eligible now (see that field's own doc). On a failed charge, the
 * plot's current worker (if any) is removed directly via {@link
 * NpcHireMaintenance#despawnHiredWorker} -- {@code npcHired} itself is deliberately left {@code
 * true}, so a later day where the plot is funded again (whether by waiting for the next midnight or
 * by clicking "Hire NPC" early) automatically restores it, per the user's own spec: "the NPC will
 * despawn until enough is available the following midnight, or the player clicks 'Hire NPC'."
 *
 * <p><b>"Plot Rent will be pulled first, and then NPC pay"</b> (explicit user spec) -- Rent is an
 * entirely separate mechanism ({@code bills.PlotRentTicker} registers a Yconomics bill at Finalize;
 * the actual recurring charge happens inside *Yconomics'* own {@code bills.BillProcessingTicker},
 * not here), and both ultimately draw from the same pool (a plot's own physical boxes), so ordering
 * between the two mods' tickers matters. Registered at {@link EventPriority#LOWEST} -- NeoForge's
 * event bus runs same-event subscribers across every mod in one global priority order, not per-mod,
 * so this reliably runs after Yconomics' own (default-priority) rent processing within the same
 * server tick, regardless of registration order between the two mods.
 */
public final class NpcMaintenanceTicker {

    private static final long TICKS_PER_DAY = 24000L;

    private long lastKnownDay = -1;

    @SubscribeEvent(priority = EventPriority.LOWEST)
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

        for (Map.Entry<UUID, GlobalPos> entry : PlotConfigSignIndex.get(server).all().entrySet()) {
            GlobalPos pos = entry.getValue();
            ServerLevel level = server.getLevel(pos.dimension());
            if (level == null) {
                continue;
            }
            chargePlot(level, entry.getKey(), pos.pos(), currentDay);
        }
    }

    private void chargePlot(ServerLevel level, UUID plotId, BlockPos signPos, long currentDay) {
        if (!(level.getBlockEntity(signPos) instanceof PlotConfigSignBlockEntity sign)
                || sign.settlementCoreId() == null
                || !(level.getEntity(sign.settlementCoreId()) instanceof GhostTownHallCoreEntity core)) {
            return; // Chunk not loaded, or the sign/core couldn't be resolved -- skip this day.
        }
        PlotRecord plot = core.getPlots().stream().filter(p -> p.plotId().equals(plotId)).findFirst().orElse(null);
        if (plot == null || !plot.npcHired() || plot.npcLastChargedDay() == currentDay) {
            return; // Not hired, or already paid today via an earlier manual "Hire NPC" click.
        }
        int cost = NpcHireMaintenance.costFor(plot);
        if (NpcHireMaintenance.tryCharge(level, plotId, cost)) {
            core.updatePlot(plot.withNpcLastChargedDay(currentDay));
        } else {
            NpcHireMaintenance.despawnHiredWorker(level, sign);
        }
    }
}
