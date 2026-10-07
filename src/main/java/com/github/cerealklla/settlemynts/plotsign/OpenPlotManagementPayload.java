package com.github.cerealklla.settlemynts.plotsign;

import java.util.ArrayList;
import java.util.List;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server-to-client: opens {@code client.PlotManagementScreen} with every plot in the settlement. */
public record OpenPlotManagementPayload(List<PlotSummaryEntry> plots) implements CustomPacketPayload {

    public static final Type<OpenPlotManagementPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "open_plot_management"));

    public static final StreamCodec<ByteBuf, OpenPlotManagementPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.collection(ArrayList::new, PlotSummaryEntry.STREAM_CODEC), OpenPlotManagementPayload::plots,
            OpenPlotManagementPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
