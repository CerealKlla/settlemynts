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
        // Collected (not settled per-plot) so PlannedInventoryClearing.settleGroup runs exactly once
        // per settlement -- PlotConfigSignIndex is a flat server-wide map, not grouped by core.
        java.util.Set<GhostTownHallCoreEntity> cores = new java.util.HashSet<>();
        for (Map.Entry<UUID, GlobalPos> entry : PlotConfigSignIndex.get(server).all().entrySet()) {
            GlobalPos pos = entry.getValue();
            ServerLevel level = server.getLevel(pos.dimension());
            if (level == null) {
                continue;
            }
            GhostTownHallCoreEntity core = restockPlot(level, entry.getKey(), pos.pos());
            if (core != null) {
                cores.add(core);
            }
        }
        for (GhostTownHallCoreEntity core : cores) {
            if (core.level() instanceof ServerLevel level) {
                PlannedInventoryClearing.settleGroup(level, core.getPlots());
                // Explicit third step (2026-10-10 redesign): turn whatever just changed hands above
                // into finished goods, for this same settlement only -- see that method's own doc.
                PlannedInventoryClearing.craftFromOwnStockForGroup(level, core.getPlots());
                // Fourth step, same day (explicit follow-up: "that way npc villages could
                // automatically list food separated by value") -- auto-list whatever crafted-food
                // quality variants just got produced above, for every NPC-owned (no player owner)
                // plot nobody will necessarily ever open Manage Shop for. A player-owned plot is left
                // alone -- its owner curates their own Shop via Manage Shop, same as every other
                // listing. BlockPos.ZERO mirrors restockNaturalShop's own fallback right below --
                // this only ever affects biome-gated catalog pricing for the dish's plain base price,
                // which crafted dishes aren't gated on anyway.
                for (PlotRecord plot : core.getPlots()) {
                    if (plot.owner().isEmpty()) {
                        ShopSeeding.autoListCraftedFoodVariants(level, plot, BlockPos.ZERO);
                    }
                }
            }
        }
        restockNaturalSettlementShops(server);
    }

    /**
     * Same daily restock, for natural-settlement Shops (added 2026-10-08, explicit user request:
     * "the same logic applied everywhere" -- these intentionally share {@link
     * ShopSeeding#restockAtMidnight}'s own {@code NUGGET_FLOOR}, not a separate value). A natural
     * settlement's Shop is shared across every plot of that Zone Type (see {@code
     * NaturalVillagePlotGenerator}), so only one representative plot per (settlement, Zone Type) is
     * restocked -- {@code api.Settlemynts#resolvePlotBoxes} resolves any of them to the exact same
     * underground vault anyway (see {@code zone.NaturalShopVault}).
     *
     * <p>Calls {@link ShopSeeding#restockNaturalShop}, not {@link ShopSeeding#restockAtMidnight} --
     * same real bug and reasoning as {@code ShopSeeding#seedNewPlotSharingShop}'s own doc: a shop
     * with no owner needs plain goods actually restocked (and their listings kept alive), which
     * {@code restockAtMidnight} deliberately no longer does for a player-owned plot's own good reason
     * (an owner's own production should supply those, not a daily conjure). The gold-nugget floor is
     * still the one piece both paths share verbatim.
     */
    private void restockNaturalSettlementShops(MinecraftServer server) {
        ServerLevel level = server.overworld(); // Villages are overworld-only.
        if (level == null) {
            return;
        }
        for (Map.Entry<SettlementKey, java.util.List<PlotRecord>> settlementEntry : NaturalSettlementPlotStore.get(server).all().entrySet()) {
            java.util.List<PlotRecord> plots = settlementEntry.getValue();
            NaturalSettlementPlotOwner owner = new NaturalSettlementPlotOwner(server, settlementEntry.getKey());
            java.util.Map<net.minecraft.resources.Identifier, PlotRecord> representativeByZone = new java.util.HashMap<>();
            for (PlotRecord plot : plots) {
                representativeByZone.putIfAbsent(plot.zoneTypeId(), plot);
            }
            for (PlotRecord representative : representativeByZone.values()) {
                if (representative.shopId().isEmpty()) {
                    continue;
                }
                ShopSeeding.restockNaturalShop(level, owner, representative, BlockPos.ZERO);
            }
            PlannedInventoryClearing.settleGroup(level, plots);
        }
    }

    /** Returns the resolved core (or {@code null}) so the caller can settle Planned Inventory once per settlement -- see this call site's own comment. */
    private GhostTownHallCoreEntity restockPlot(ServerLevel level, UUID plotId, BlockPos signPos) {
        if (!(level.getBlockEntity(signPos) instanceof PlotConfigSignBlockEntity sign)
                || sign.settlementCoreId() == null
                || !(level.getEntity(sign.settlementCoreId()) instanceof GhostTownHallCoreEntity core)) {
            return null; // Chunk not loaded, or the sign/core couldn't be resolved -- skip this day.
        }
        PlotRecord plot = core.getPlots().stream().filter(p -> p.plotId().equals(plotId)).findFirst().orElse(null);
        if (plot == null) {
            return null;
        }
        ShopSeeding.restockAtMidnight(level, plot, signPos);
        return core;
    }
}
