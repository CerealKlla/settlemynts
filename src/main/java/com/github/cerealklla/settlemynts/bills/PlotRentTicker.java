package com.github.cerealklla.settlemynts.bills;

import java.util.List;
import java.util.Optional;

import com.github.cerealklla.settlemynts.bridge.YconomicsBillBridge;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.registration.ModEntities;
import com.github.cerealklla.settlemynts.zone.PlotRecord;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Keeps every player-owned plot's recurring rent bill pointed at whatever real boxes currently
 * exist, 2026-10-05 (see decisions.md) -- a plot's own box (wherever a player placed one inside
 * that plot) is the bill's source; the settlement's single Town-Hall-typed plot's own box(es) are
 * the shared destination for every plot in that settlement (explicit user decision: "The
 * destination is the settlemynts city hall plot"). Neither box is auto-placed -- both are
 * discovered live via {@link PlotBoxDiscovery}, so a plot with no box placed yet simply resolves
 * to an empty list (reported as {@code INSUFFICIENT_FUNDS}/{@code MISSING_BOX} at processing time,
 * and surfaced as a real issue on the Plot Management screens -- see {@code PlotBillingStatus}).
 *
 * <p>Same periodic-scan shape as {@code construction.NpcAutoFundingTicker} (a single large-radius
 * world scan rather than a maintained registry -- only currently-loaded settlements/plots are
 * reachable this way, same known limitation that ticker already has).
 */
public class PlotRentTicker {

    private static final int SCAN_INTERVAL_TICKS = 20;
    private static final double WORLD_SCAN_RADIUS = 3.0E7;

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (!YconomicsBillBridge.isAvailable()) {
            return;
        }
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % SCAN_INTERVAL_TICKS != 0) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            AABB worldBounds = new AABB(-WORLD_SCAN_RADIUS, level.getMinY(), -WORLD_SCAN_RADIUS,
                    WORLD_SCAN_RADIUS, level.getMaxY(), WORLD_SCAN_RADIUS);
            List<GhostTownHallCoreEntity> cores = level.getEntities(ModEntities.GHOST_TOWN_HALL_CORE.get(), worldBounds,
                    GhostTownHallCoreEntity::isFinalized);
            for (GhostTownHallCoreEntity core : cores) {
                syncSettlement(level, core);
            }
        }
    }

    private void syncSettlement(ServerLevel level, GhostTownHallCoreEntity core) {
        Optional<PlotRecord> townHallPlot = core.findTownHallPlot(level);
        List<YconomicsBillBridge.BoxRef> destinationBoxes = townHallPlot
                .map(plot -> PlotBoxDiscovery.findBoxes(level, plot.cartographyrPlotEntityId()))
                .orElse(List.of());

        for (PlotRecord plot : core.getPlots()) {
            if (plot.owner().isEmpty() || plot.billId().isEmpty()) {
                continue;
            }
            List<YconomicsBillBridge.BoxRef> sourceBoxes = PlotBoxDiscovery.findBoxes(level, plot.cartographyrPlotEntityId());
            YconomicsBillBridge.updateBillBoxes(level, plot.billId().get(), sourceBoxes, destinationBoxes);
        }
    }
}
