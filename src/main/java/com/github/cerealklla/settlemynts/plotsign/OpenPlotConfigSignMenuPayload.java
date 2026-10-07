package com.github.cerealklla.settlemynts.plotsign;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server-to-client: opens {@code client.PlotConfigSignMenuScreen} (design doc Section 14a's "Main
 * Menu"). {@code canManage} is resolved once, server-side ({@code PlotConfigSignBlock#useWithoutItem}
 * via {@code zone.PlotPermissions#canManage}) -- the client never re-derives permission itself, it
 * only decides which buttons to show/enable based on this. {@code hasGarrison} (added 2026-09-30) is
 * {@code true} only for a Guardhouse-typed plot -- gates the "Configure Garrison" button.
 */
public record OpenPlotConfigSignMenuPayload(BlockPos signPos, boolean canManage, boolean hasGarrison, boolean isTownHall) implements CustomPacketPayload {

    public static final Type<OpenPlotConfigSignMenuPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "open_plot_config_sign_menu"));

    public static final StreamCodec<ByteBuf, OpenPlotConfigSignMenuPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), OpenPlotConfigSignMenuPayload::signPos,
            ByteBufCodecs.BOOL, OpenPlotConfigSignMenuPayload::canManage,
            ByteBufCodecs.BOOL, OpenPlotConfigSignMenuPayload::hasGarrison,
            ByteBufCodecs.BOOL, OpenPlotConfigSignMenuPayload::isTownHall,
            OpenPlotConfigSignMenuPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
