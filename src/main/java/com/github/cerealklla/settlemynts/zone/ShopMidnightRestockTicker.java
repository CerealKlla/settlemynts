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
    // Real production incident, 2026-10-20: the heavy per-settlement steps below used to run fully
    // synchronously the instant a day boundary was detected, and a single settlement's own
    // PlannedInventoryClearing#settleGroup pass took 57 real seconds end to end -- long enough to
    // trip the dedicated server's 60-second ServerWatchdog and force-crash the whole process mid-tick.
    // See MidnightWorkQueue's own doc for the full writeup and why this can't just move to a real
    // background thread instead.
    private final MidnightWorkQueue workQueue = new MidnightWorkQueue();

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        workQueue.pump();
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
        // Real production incident, 2026-10-21: this whole method used to run every one of its steps
        // fully synchronously the instant a day boundary was detected (the sign/core resolution loop
        // below included) -- after three separate single-tick freezes were each traced to one
        // unchunked step deeper in this same pipeline (see PlannedInventoryClearing/
        // GuardhouseFoodAutoBuy/DefenseSpendingTransfer's own decisions.md entries), every remaining
        // step here was audited and queued too, proactively, rather than waiting for a fourth
        // incident to point at whichever one was still unchunked. `pipelinedCoreIds` dedupes so each
        // settlement's heavy pipeline still runs exactly once even though several signs can resolve
        // to the same core -- safe with no locking since queued jobs only ever run one at a time, on
        // this same thread.
        java.util.Set<UUID> pipelinedCoreIds = new java.util.HashSet<>();
        for (Map.Entry<UUID, GlobalPos> entry : PlotConfigSignIndex.get(server).all().entrySet()) {
            GlobalPos pos = entry.getValue();
            ServerLevel level = server.getLevel(pos.dimension());
            if (level == null) {
                continue;
            }
            UUID plotId = entry.getKey();
            BlockPos signPos = pos.pos();
            workQueue.enqueue(() -> {
                GhostTownHallCoreEntity core = restockPlot(level, plotId, signPos);
                if (core != null && pipelinedCoreIds.add(core.getUUID())) {
                    enqueueSettlementPipeline(level, core);
                }
            });
        }
        enqueueNaturalSettlementShops(server);
    }

    /**
     * The per-settlement midnight pipeline (Defense Spending, inter-plot trading, own-stock
     * crafting, Guardhouse food buying, crafted-food auto-listing) -- extracted from {@link
     * #onServerTick} so it can be invoked from inside an already-queued job (see that method's own
     * doc for why resolving which core a sign belongs to is now itself deferred).
     */
    private void enqueueSettlementPipeline(ServerLevel level, GhostTownHallCoreEntity core) {
        // "Defense Spending" (explicit user spec, 2026-10-16) -- transfers gold from the Town
        // Hall's own boxes into each Guardhouse's boxes BEFORE the Planned-Inventory settlement
        // and food auto-buy below run, so gold moved this same tick is already spendable the
        // same night, not just starting the next one. See DefenseSpendingTransfer's own doc.
        // Enqueued (not called directly) so it still runs strictly before the settleGroup jobs
        // enqueued right after it -- MidnightWorkQueue is FIFO, so queue order is run order.
        com.github.cerealklla.settlemynts.guardhouse.DefenseSpendingTransfer.enqueueForSettlement(level, core, workQueue);
        PlannedInventoryClearing.settleGroup(level, core.getPlots(), workQueue);
        // Explicit third step (2026-10-10 redesign): turn whatever just changed hands above
        // into finished goods, for this same settlement only -- see that method's own doc.
        PlannedInventoryClearing.enqueueCraftFromOwnStockForGroup(level, core.getPlots(), workQueue);
        // "Preferred Food" (explicit user spec, 2026-10-16) -- buys any Guardhouse-typed
        // plot's food store up toward its own threshold, same day-boundary as everything
        // above. See GuardhouseFoodAutoBuy's own doc for the full mechanism.
        com.github.cerealklla.settlemynts.guardhouse.GuardhouseFoodAutoBuy.enqueueForSettlement(level, core, workQueue);
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
                workQueue.enqueue(() -> ShopSeeding.autoListCraftedFoodVariants(level, plot, BlockPos.ZERO));
            }
        }
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
    private void enqueueNaturalSettlementShops(MinecraftServer server) {
        ServerLevel level = server.overworld(); // Villages are overworld-only.
        if (level == null) {
            return;
        }
        // One job per natural settlement, not a synchronous loop over all of them -- same
        // proactive chunking as everything else in this pipeline, see onServerTick's own doc.
        for (Map.Entry<SettlementKey, java.util.List<PlotRecord>> settlementEntry : NaturalSettlementPlotStore.get(server).all().entrySet()) {
            java.util.List<PlotRecord> plots = settlementEntry.getValue();
            SettlementKey settlementKey = settlementEntry.getKey();
            workQueue.enqueue(() -> {
                NaturalSettlementPlotOwner owner = new NaturalSettlementPlotOwner(server, settlementKey);
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
                PlannedInventoryClearing.settleGroup(level, plots, workQueue);
            });
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
