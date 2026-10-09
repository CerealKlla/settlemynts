package com.github.cerealklla.settlemynts.plotsign;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client-to-server: "Upgrade Plot" button click on {@code client.PlotConfigSignMenuScreen} -- requests a live, server-computed cost/affordability preview (see {@code construction.PlotTierUpgradeFunding#preview}) before opening {@code client.UpgradePlotScreen}, rather than the screen computing/displaying anything itself (added 2026-10-09, explicit follow-up request after the first live test: show current on-plot amounts, live cheapest-price gold costs, and disable buttons the settlement can't actually supply). */
public record RequestUpgradePlotPreviewPayload(BlockPos signPos) implements CustomPacketPayload {

    public static final Type<RequestUpgradePlotPreviewPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "request_upgrade_plot_preview"));

    public static final StreamCodec<ByteBuf, RequestUpgradePlotPreviewPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), RequestUpgradePlotPreviewPayload::signPos,
            RequestUpgradePlotPreviewPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
