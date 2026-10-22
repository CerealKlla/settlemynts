package com.github.cerealklla.settlemynts.zone;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.github.cerealklla.settlemynts.api.Settlemynts;
import com.github.cerealklla.settlemynts.bridge.BlueprintsConstructionBridge;
import com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.founding.SettlementCoreIndex;
import com.github.cerealklla.settlemynts.guardhouse.GuardhouseConstants;
import com.github.cerealklla.settlemynts.guardhouse.GuardhouseIndex;
import com.github.cerealklla.settlemynts.plotsign.PlotConfigSignIndex;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Drives {@link ShopSeeding#restockAtMidnight} once per real in-game day boundary (added 2026-10-05,
 * explicit user request) -- same day-boundary detection shape as Yconomics' own {@code
 * bills.BillProcessingTicker} (a local, duplicated two-line day-number calculation rather than a new
 * cross-mod dependency for something this small/pure).
 *
 * <p><b>Rewritten 2026-10-22</b> for the "midnight settlement processing must run regardless of
 * player proximity" requirement (see decisions.md) -- every known settlement is now discovered
 * directly via {@link SettlementCoreIndex} (a flat, chunk-independent registry) instead of being
 * derived indirectly by iterating every Plot Config Sign and hoping its chunk happens to already be
 * loaded. Each settlement's own chunk, plus every position its midnight pipeline will touch, is
 * force-loaded (see {@link MidnightChunkWaker}) for the duration of that settlement's own job
 * sequence, then released -- "wake the whole settlement, do every transaction, let it rest on its
 * own time," per the user's own explicit framing, rather than per-job force/unforce churn.
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
        // One job per settlement, discovered directly from SettlementCoreIndex (flat, chunk-
        // independent -- see this class's own doc) rather than derived indirectly from signs. Each
        // job wakes its own settlement (see wakeAndPipelineSettlement) before doing anything else;
        // MidnightWorkQueue's own ambient settlement-context propagation means every job this one
        // goes on to enqueue (the restock loop, PlannedInventoryClearing, GuardhouseFoodAutoBuy,
        // DefenseSpendingTransfer, and any of their own self-re-enqueued continuations) automatically
        // stays tagged under this same settlement, with no changes needed in any of those classes.
        for (Map.Entry<UUID, GlobalPos> entry : SettlementCoreIndex.get(server).all().entrySet()) {
            UUID coreId = entry.getKey();
            GlobalPos corePos = entry.getValue();
            ServerLevel level = server.getLevel(corePos.dimension());
            if (level == null) {
                continue;
            }
            BlockPos coreBlockPos = corePos.pos();
            workQueue.enqueueForSettlement(level, coreId, () -> wakeAndPipelineSettlement(level, coreId, coreBlockPos));
        }
        enqueueNaturalSettlementShops(server);
    }

    /**
     * Force-loads this settlement's own core chunk, resolves its real plot list, force-loads every
     * position that list implies (sign, every registered box, Construction Box, Guardhouse), then
     * enqueues the rest of this settlement's midnight pipeline exactly as before. The two-step wake
     * (core first, then everything else) is unavoidable -- the plot list itself only exists on the
     * live core entity, so there's no way to know what else needs loading before that first chunk is.
     */
    private void wakeAndPipelineSettlement(ServerLevel level, UUID coreId, BlockPos coreBlockPos) {
        MidnightChunkWaker.wake(level, coreId, List.of(coreBlockPos));
        if (!(level.getEntity(coreId) instanceof GhostTownHallCoreEntity core)) {
            return; // Shouldn't happen now that its own chunk is forced -- stay defensive, not fatal.
        }

        List<BlockPos> wakePositions = new ArrayList<>();
        for (PlotRecord plot : core.getPlots()) {
            PlotConfigSignIndex.get(level.getServer()).get(plot.plotId()).ifPresent(pos -> wakePositions.add(pos.pos()));
            wakePositions.addAll(Settlemynts.resolvePlotBoxPositions(level, plot.plotId()));
            if (ModList.get().isLoaded("blueprynts")) {
                BlueprintsConstructionBridge.resolveLivePos(level, plot).ifPresent(wakePositions::add);
            }
            if (plot.zoneTypeId().equals(GuardhouseConstants.GUARDHOUSE_ZONE_TYPE_ID)) {
                GuardhouseIndex.get(level.getServer()).get(plot.plotId()).ifPresent(pos -> wakePositions.add(pos.pos()));
            }
        }
        MidnightChunkWaker.wake(level, coreId, wakePositions);

        for (PlotRecord plot : core.getPlots()) {
            PlotConfigSignIndex.get(level.getServer()).get(plot.plotId())
                    .ifPresent(signPos -> workQueue.enqueue(() -> ShopSeeding.restockAtMidnight(level, plot, signPos.pos())));
        }
        enqueueSettlementPipeline(level, core);
    }

    /**
     * The per-settlement midnight pipeline (Defense Spending, inter-plot trading, own-stock
     * crafting, Guardhouse food buying, crafted-food auto-listing).
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
     * <p>Woken the same way a player-founded settlement is (explicit requirement, 2026-10-22) --
     * every zone type's own underground vault position, from {@link NaturalShopVaultIndex} (flat,
     * chunk-independent, same as every other index this feature relies on) -- keyed by the same
     * deterministic settlement id {@code api.Settlemynts#findNaturalPlotAt} already derives for a
     * natural village with no real core entity to use instead ({@code new UUID(worldSeed,
     * settlementEntityId)}).
     */
    private void enqueueNaturalSettlementShops(MinecraftServer server) {
        ServerLevel level = server.overworld(); // Villages are overworld-only.
        if (level == null) {
            return;
        }
        for (Map.Entry<SettlementKey, List<PlotRecord>> settlementEntry : NaturalSettlementPlotStore.get(server).all().entrySet()) {
            List<PlotRecord> plots = settlementEntry.getValue();
            SettlementKey settlementKey = settlementEntry.getKey();
            UUID settlementId = new UUID(settlementKey.worldSeed(), settlementKey.settlementEntityId());

            Map<Identifier, PlotRecord> representativeByZone = new HashMap<>();
            for (PlotRecord plot : plots) {
                representativeByZone.putIfAbsent(plot.zoneTypeId(), plot);
            }
            List<BlockPos> wakePositions = new ArrayList<>();
            for (Identifier zoneTypeId : representativeByZone.keySet()) {
                NaturalShopVaultIndex.get(server).get(settlementKey, zoneTypeId).ifPresent(wakePositions::add);
            }

            workQueue.enqueueForSettlement(level, settlementId, () -> {
                MidnightChunkWaker.wake(level, settlementId, wakePositions);
                NaturalSettlementPlotOwner owner = new NaturalSettlementPlotOwner(server, settlementKey);
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
}
