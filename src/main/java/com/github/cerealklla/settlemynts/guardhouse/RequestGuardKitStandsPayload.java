package com.github.cerealklla.settlemynts.guardhouse;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: "Get Guard Kit Stands" button on {@code plotsign.client.PlotConfigSignMenuScreen}.
 * {@code signPos} resolves back to the plot the same way every other Plot Config Sign button does;
 * the server re-checks {@code zone.PlotPermissions#canManage} and re-reads the plot's own live {@code
 * zone.PlotRecord#guardKitStandsIssued} count (never trusts the button's earlier label) before
 * granting exactly one Guard Kit Stand item and incrementing that count -- see {@code
 * SettlemyntsMod#requestGuardKitStands}.
 */
public record RequestGuardKitStandsPayload(BlockPos signPos) implements CustomPacketPayload {

    public static final Type<RequestGuardKitStandsPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "request_guard_kit_stands"));

    public static final StreamCodec<ByteBuf, RequestGuardKitStandsPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), RequestGuardKitStandsPayload::signPos,
            RequestGuardKitStandsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
