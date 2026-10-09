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
        for (java.util.List<PlotRecord> plots : NaturalSettlementPlotStore.get(server).all().values()) {
            java.util.Map<net.minecraft.resources.Identifier, PlotRecord> representativeByZone = new java.util.HashMap<>();
            for (PlotRecord plot : plots) {
                representativeByZone.putIfAbsent(plot.zoneTypeId(), plot);
            }
            for (PlotRecord representative : representativeByZone.values()) {
                if (representative.shopId().isEmpty()) {
                    continue;
                }
                ShopSeeding.restockNaturalShop(level, representative, BlockPos.ZERO);
            }
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
