package com.github.cerealklla.settlemynts.zone;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.github.cerealklla.settlemynts.bridge.BlueprintsConstructionBridge;
import com.github.cerealklla.settlemynts.bridge.YconomicsBillBridge;

import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;

/**
 * Writes a paid rent bill to both sides' own Construction Box ledgers -- added 2026-10-12, real
 * user request: "I was hoping to see rent entries in the town hall ledger," confirmed to mean each
 * individual plot's own ledger showing its own incoming/outgoing transfers (not one shared
 * settlement-wide feed -- see decisions.md). Registers against {@code
 * bridge.YconomicsBillBridge#registerBillProcessedListener}, which fires for *every* recurring bill
 * this server processes, not just plot rent -- this listener only acts when {@code ownerContext}
 * resolves to a real plot (every rent bill this mod registers tags it with the paying plot's id via
 * {@code registerPlotBill}; a bill registered by some unrelated future consumer with no plot context
 * is silently ignored here).
 *
 * <p>Each side only gets a representative counterpart name (the first resolvable plot among the
 * *other* side's own box positions), not a precise per-box breakdown -- a deliberate simplification:
 * a rent bill only ever has one real source plot and one real destination (the settlement's Town
 * Hall), so in practice there's nothing to disambiguate; the multi-box plumbing underneath (a plot
 * can have more than one box physically inside it) is {@code PlotRentTicker}'s own existing
 * flexibility, not something this ledger entry needs to resolve exactly.
 */
public final class RentLedgerListener {

    private RentLedgerListener() {
    }

    public static void register() {
        YconomicsBillBridge.registerBillProcessedListener(RentLedgerListener::onBillProcessed);
    }

    private static void onBillProcessed(MinecraftServer server, UUID billId, Optional<UUID> ownerContext,
                                         List<GlobalPos> sourceBoxPositions, List<GlobalPos> destinationBoxPositions,
                                         Map<Identifier, Integer> cost, String outcomeName) {
        if (!"PAID".equals(outcomeName) || ownerContext.isEmpty() || !ModList.get().isLoaded("blueprynts")) {
            return;
        }
        String costSummary = formatCost(cost);
        String payingPlotName = resolvePlotName(server, sourceBoxPositions).orElse("a plot");
        String receivingPlotName = resolvePlotName(server, destinationBoxPositions).orElse("the settlement");

        for (GlobalPos pos : sourceBoxPositions) {
            BlueprintsConstructionBridge.appendLedgerEntry(levelOf(server, pos), pos.pos(),
                    "Paid " + costSummary + " rent to " + receivingPlotName);
        }
        for (GlobalPos pos : destinationBoxPositions) {
            BlueprintsConstructionBridge.appendLedgerEntry(levelOf(server, pos), pos.pos(),
                    "Received " + costSummary + " rent from " + payingPlotName);
        }
    }

    private static ServerLevel levelOf(MinecraftServer server, GlobalPos pos) {
        return server.getLevel(pos.dimension());
    }

    private static Optional<String> resolvePlotName(MinecraftServer server, List<GlobalPos> boxPositions) {
        for (GlobalPos pos : boxPositions) {
            ServerLevel level = levelOf(server, pos);
            if (level == null) {
                continue;
            }
            Optional<String> name = com.github.cerealklla.settlemynts.api.Settlemynts.findPlotAt(level, pos.pos())
                    .flatMap(handle -> com.github.cerealklla.settlemynts.api.Settlemynts.findPlotRecord(level, handle.plotId()))
                    .map(PlotRecord::name);
            if (name.isPresent()) {
                return name;
            }
        }
        return Optional.empty();
    }

    private static String formatCost(Map<Identifier, Integer> cost) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Identifier, Integer> entry : cost.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            String itemName = BuiltInRegistries.ITEM.getOptional(entry.getKey())
                    .map(item -> item.getDefaultInstance().getHoverName().getString())
                    .orElse(entry.getKey().getPath());
            sb.append(entry.getValue()).append("x ").append(itemName);
        }
        return sb.length() == 0 ? "0" : sb.toString();
    }
}
