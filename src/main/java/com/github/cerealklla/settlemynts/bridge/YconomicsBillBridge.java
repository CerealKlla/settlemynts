package com.github.cerealklla.settlemynts.bridge;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.github.cerealklla.yconomics.api.Yconomics;
import com.github.cerealklla.yconomics.bills.RecurringBill;

import net.minecraft.core.GlobalPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;

/**
 * Registers a plot's recurring rent bill against Yconomics' bill engine (2026-10-05, see
 * decisions.md) -- this class must only ever be referenced (its methods called, or this class
 * loaded/classload-triggered at all) from behind a {@code ModList.get().isLoaded("yconomics")}
 * check at the call site, same isolation every other optional sibling-mod dependency in this
 * suite uses, so a Yconomics-less server never force-loads Yconomics classes.
 *
 * <p>Takes/returns its own local {@link BoxRef} rather than Yconomics' {@code
 * RecurringBill.BoxRef} directly at the public boundary -- only converts to the real Yconomics
 * type internally, so no other Settlemynts class ever needs its own reference to a Yconomics type
 * either, even one already behind this bridge.
 */
public final class YconomicsBillBridge {

    private YconomicsBillBridge() {
    }

    public static boolean isAvailable() {
        return ModList.get().isLoaded("yconomics");
    }

    /** A box's identity plus its current position -- Settlemynts' own local shape, mirrors Yconomics' {@code RecurringBill.BoxRef}. */
    public record BoxRef(UUID id, GlobalPos pos) {
    }

    public static UUID registerPlotBill(ServerLevel level, UUID plotId, List<BoxRef> sourceBoxes,
                                         List<BoxRef> destinationBoxes, Map<Identifier, Integer> cost, long periodDays) {
        return Yconomics.registerRecurringBill(level, toYconomics(sourceBoxes), toYconomics(destinationBoxes),
                cost, periodDays, Optional.of(plotId));
    }

    /** Overwrites a bill's box lists outright -- see {@code bills.PlotBillBoxListener}, which reads {@link #getBillBoxes} first and writes back an incrementally add/removed copy, never a full rediscovery. */
    public static void updateBillBoxes(ServerLevel level, UUID billId, List<BoxRef> sourceBoxes, List<BoxRef> destinationBoxes) {
        Yconomics.setRecurringBillBoxes(level, billId, toYconomics(sourceBoxes), toYconomics(destinationBoxes));
    }

    /** A bill's current source/destination box lists -- added 2026-10-22 for {@code bills.PlotBillBoxListener}'s own event-driven incremental add/remove (reads the current list, then writes back the one changed entry via {@link #updateBillBoxes}). */
    public record BillBoxes(List<BoxRef> sourceBoxes, List<BoxRef> destinationBoxes) {
    }

    public static Optional<BillBoxes> getBillBoxes(ServerLevel level, UUID billId) {
        return Yconomics.getRecurringBill(level, billId).map(bill -> new BillBoxes(fromYconomics(bill.sourceBoxes()), fromYconomics(bill.destinationBoxes())));
    }

    /** Local mirror of Yconomics' own outcome enum -- same no-leaking-Yconomics-types-out boundary as {@link BoxRef}. */
    public enum BillOutcome {
        PAID, INSUFFICIENT_FUNDS, MISSING_BOX
    }

    /** A bill's current standing, as much as a Plot Management screen needs -- not the full Yconomics record. */
    public record BillStatus(boolean sourceEmpty, boolean destinationEmpty, boolean everProcessed, Optional<BillOutcome> lastOutcome) {
    }

    public static Optional<BillStatus> getBillStatus(ServerLevel level, UUID billId) {
        return Yconomics.getRecurringBill(level, billId).map(bill -> new BillStatus(
                bill.sourceBoxes().isEmpty(),
                bill.destinationBoxes().isEmpty(),
                bill.lastProcessedDay() > 0 || bill.lastOutcome().isPresent(),
                bill.lastOutcome().map(o -> BillOutcome.valueOf(o.name()))));
    }

    private static List<RecurringBill.BoxRef> toYconomics(List<BoxRef> refs) {
        return refs.stream().map(r -> new RecurringBill.BoxRef(r.id(), r.pos())).toList();
    }

    private static List<BoxRef> fromYconomics(List<RecurringBill.BoxRef> refs) {
        return refs.stream().map(r -> new BoxRef(r.id(), r.pos())).toList();
    }

    /** Settlemynts' own translated shape of {@code bills.BillEvents.BillProcessedListener} -- see its own doc. */
    public interface BillProcessedListener {
        void onBillProcessed(net.minecraft.server.MinecraftServer server, UUID billId, Optional<UUID> ownerContext,
                              List<net.minecraft.core.GlobalPos> sourceBoxPositions, List<net.minecraft.core.GlobalPos> destinationBoxPositions,
                              Map<Identifier, Integer> cost, String outcomeName);
    }

    /** Registers a listener for every recurring bill processed (not just this mod's own rent bills) -- see {@code zone.RentLedgerListener}. */
    public static void registerBillProcessedListener(BillProcessedListener listener) {
        Yconomics.registerBillProcessedListener(listener::onBillProcessed);
    }
}
