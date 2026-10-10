package com.github.cerealklla.settlemynts.zone;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.github.cerealklla.cartographyr.geo.Geometry;
import com.github.cerealklla.settlemynts.api.Settlemynts;
import com.github.cerealklla.settlemynts.bridge.LyfeCraftingBridge;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Event-triggered replacement for the per-shop half of NPC auto-crafting (2026-10-10 redesign,
 * explicit user request -- see {@code PlannedInventoryClearing#craftFromOwnStockForGroup}'s own doc
 * for the removed, time-based, whole-world-scanning {@code PlotCraftingTicker} this replaces).
 * {@code SettlemyntsMod#buyFromShop}/{@code #sellToShop} call {@link #markActivity} after every
 * successful trade; a shop is only actually checked for auto-crafting once it's gone quiet for
 * {@link #DEBOUNCE_TICKS} -- a player buying or selling several things in a row re-marks the same
 * shop each time, pushing the check back, so the (chunk-scanning) crafting-structure lookup only
 * ever runs once per burst of activity rather than once per click.
 */
public final class ShopCraftingDebounceTicker {

    private static final long DEBOUNCE_TICKS = 200L; // 10 real seconds at 20 ticks/sec.
    private static final int SWEEP_INTERVAL_TICKS = 20; // The sweep itself is a cheap map scan -- no need to run it more than once a second.

    private record Pending(ServerLevel level, long lastActivityTick) {
    }

    private static final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    /** Called after every successful Buy/Sell -- see class doc. */
    public static void markActivity(ServerLevel level, UUID plotId) {
        pending.put(plotId, new Pending(level, level.getServer().getTickCount()));
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (pending.isEmpty() || event.getServer().getTickCount() % SWEEP_INTERVAL_TICKS != 0) {
            return;
        }
        long now = event.getServer().getTickCount();
        pending.entrySet().removeIf(entry -> {
            if (now - entry.getValue().lastActivityTick() < DEBOUNCE_TICKS) {
                return false; // Still within the quiet window -- check again next sweep.
            }
            fire(entry.getValue().level(), entry.getKey());
            return true;
        });
    }

    private static void fire(ServerLevel level, UUID plotId) {
        if (!LyfeCraftingBridge.isLoaded()) {
            return;
        }
        Optional<PlotRecord> plotOpt = Settlemynts.findPlotRecord(level, plotId);
        if (plotOpt.isEmpty()) {
            return; // Plot no longer resolves (settlement gone, etc.) -- nothing to do.
        }
        PlotRecord plot = plotOpt.get();
        Optional<Geometry.Polygon> polygon = Settlemynts.resolvePolygonDirect(level, plot);
        if (polygon.isEmpty()) {
            return;
        }
        List<Container> boxes = Settlemynts.resolveBoxesForPolygon(level, polygon.get());
        PlannedInventoryClearing.craftFromOwnStock(level, plot, polygon.get(), boxes);
    }
}
