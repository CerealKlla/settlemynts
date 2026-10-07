package com.github.cerealklla.settlemynts.plotsign;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: "Plot Details" button on {@code client.PlotConfigSignMenuScreen} -- any plot's
 * own sign, 2026-10-05. Server resolves the plot behind {@code signPos} fresh and replies with
 * {@link OpenPlotDetailsPayload}; no permission check needed, this is read-only.
 */
public record RequestPlotDetailsPayload(BlockPos signPos) implements CustomPacketPayload {

    public static final Type<RequestPlotDetailsPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "request_plot_details"));

    public static final StreamCodec<ByteBuf, RequestPlotDetailsPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), RequestPlotDetailsPayload::signPos,
            RequestPlotDetailsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
