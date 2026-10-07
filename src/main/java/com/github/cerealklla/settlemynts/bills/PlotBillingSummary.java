package com.github.cerealklla.settlemynts.bills;

import java.util.Optional;

import com.github.cerealklla.settlemynts.bridge.YconomicsBillBridge;
import com.github.cerealklla.settlemynts.zone.PlotRecord;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * A plot's current billing standing, as much as the Plot Details/Plot Management screens need
 * (2026-10-05, see decisions.md) -- derived fresh on every request, not cached, since it's cheap
 * (one Yconomics lookup, no world scan of its own -- {@link PlotRentTicker} already does that
 * continuously in the background).
 */
public record PlotBillingSummary(String plotName, String ownerDisplay, String billingStanding, Optional<String> issue) {

    public static PlotBillingSummary summarize(ServerLevel level, PlotRecord plot) {
        String ownerDisplay = plot.owner().map(id -> resolvePlayerName(level, id)).orElse("NPC-Owned");

        if (plot.owner().isEmpty()) {
            return new PlotBillingSummary(plot.name(), ownerDisplay, "No Rent (NPC-Owned)", Optional.empty());
        }
        if (plot.billId().isEmpty() || !YconomicsBillBridge.isAvailable()) {
            return new PlotBillingSummary(plot.name(), ownerDisplay, "No Bill Registered", Optional.empty());
        }

        Optional<YconomicsBillBridge.BillStatus> status = YconomicsBillBridge.getBillStatus(level, plot.billId().get());
        if (status.isEmpty()) {
            return new PlotBillingSummary(plot.name(), ownerDisplay, "Bill Missing", Optional.empty());
        }
        YconomicsBillBridge.BillStatus s = status.get();

        String standing;
        if (!s.everProcessed()) {
            standing = "Pending First Charge";
        } else if (s.lastOutcome().isEmpty()) {
            standing = "Unknown";
        } else {
            standing = switch (s.lastOutcome().get()) {
                case PAID -> "Paid";
                case INSUFFICIENT_FUNDS -> "Delinquent (Insufficient Funds)";
                case MISSING_BOX -> "Delinquent (Missing Storage)";
            };
        }

        Optional<String> issue;
        if (s.sourceEmpty() && s.destinationEmpty()) {
            issue = Optional.of("No storage placed in plot or City Hall");
        } else if (s.sourceEmpty()) {
            issue = Optional.of("No storage placed in plot");
        } else if (s.destinationEmpty()) {
            issue = Optional.of("No storage placed in City Hall");
        } else {
            issue = Optional.empty();
        }

        return new PlotBillingSummary(plot.name(), ownerDisplay, standing, issue);
    }

    /** Offline owners fall back to a raw UUID -- same known v1 limitation as owner assignment itself (online players only). */
    private static String resolvePlayerName(ServerLevel level, java.util.UUID id) {
        ServerPlayer online = level.getServer().getPlayerList().getPlayer(id);
        return online != null ? online.getGameProfile().name() : id.toString();
    }
}
