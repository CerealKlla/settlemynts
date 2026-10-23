package com.github.cerealklla.settlemynts.plotsign;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: the "Configure" tab on {@code client.PlotConfigScreen} (tabbed Plot Config Sign
 * rework, 2026-10-23) sends this on {@code onShown()} to fetch the plot's current {@code
 * mineshaft.MineSpecialization} for highlighting the right Ore/Stone button. Server replies with
 * {@link OpenConfigurePlotPayload}; no permission check needed, read-only.
 */
public record RequestConfigurePlotPayload(BlockPos signPos) implements CustomPacketPayload {

    public static final Type<RequestConfigurePlotPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "request_configure_plot"));

    public static final StreamCodec<ByteBuf, RequestConfigurePlotPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), RequestConfigurePlotPayload::signPos,
            RequestConfigurePlotPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
