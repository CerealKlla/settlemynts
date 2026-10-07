package com.github.cerealklla.settlemynts.plotsign;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: "Plot Management" button on {@code client.PlotConfigSignMenuScreen} -- only
 * ever shown on the Town Hall plot's own sign (2026-10-05, explicit user decision: a city-hall-wide
 * admin view listing every plot in the settlement, not a per-plot screen). Server re-checks {@code
 * canManage} itself, same precedent as "Configure Garrison."
 */
public record RequestPlotManagementPayload(BlockPos signPos) implements CustomPacketPayload {

    public static final Type<RequestPlotManagementPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "request_plot_management"));

    public static final StreamCodec<ByteBuf, RequestPlotManagementPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), RequestPlotManagementPayload::signPos,
            RequestPlotManagementPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
