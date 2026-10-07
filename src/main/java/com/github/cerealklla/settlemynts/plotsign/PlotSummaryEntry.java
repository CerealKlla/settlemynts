package com.github.cerealklla.settlemynts.plotsign;

import java.util.Optional;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Network-shaped mirror of {@code bills.PlotBillingSummary} (2026-10-05) -- kept separate rather
 * than giving that record its own {@code StreamCodec} so {@code bills} never needs a network
 * dependency; both {@link OpenPlotDetailsPayload} (one entry, a plot's own sign) and {@link
 * OpenPlotManagementPayload} (every plot, the Town Hall sign's admin view) reuse this shape.
 */
public record PlotSummaryEntry(String plotName, String ownerDisplay, String billingStanding, Optional<String> issue) {

    public static final StreamCodec<ByteBuf, PlotSummaryEntry> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, PlotSummaryEntry::plotName,
            ByteBufCodecs.STRING_UTF8, PlotSummaryEntry::ownerDisplay,
            ByteBufCodecs.STRING_UTF8, PlotSummaryEntry::billingStanding,
            ByteBufCodecs.optional(ByteBufCodecs.STRING_UTF8), PlotSummaryEntry::issue,
            PlotSummaryEntry::new);
}
