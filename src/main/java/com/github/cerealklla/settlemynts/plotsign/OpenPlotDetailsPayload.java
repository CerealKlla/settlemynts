package com.github.cerealklla.settlemynts.plotsign;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server-to-client: opens {@code client.PlotDetailsScreen} with this one plot's billing summary. */
public record OpenPlotDetailsPayload(PlotSummaryEntry entry) implements CustomPacketPayload {

    public static final Type<OpenPlotDetailsPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "open_plot_details"));

    public static final StreamCodec<ByteBuf, OpenPlotDetailsPayload> STREAM_CODEC = StreamCodec.composite(
            PlotSummaryEntry.STREAM_CODEC, OpenPlotDetailsPayload::entry,
            OpenPlotDetailsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
