package com.github.cerealklla.settlemynts.guardhouse;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: "Configure Garrison" button on {@code plotsign.client.PlotConfigSignMenuScreen}.
 * {@code signPos} resolves back to the plot the same way every other Plot Config Sign button does;
 * the server re-checks {@code zone.PlotPermissions#canManage} itself (never trusts the client's
 * earlier {@code canManage} flag) and resolves the plot's Guardhouse via {@link GuardhouseIndex}
 * before replying with {@link OpenConfigureGarrisonPayload}.
 */
public record RequestConfigureGarrisonPayload(BlockPos signPos) implements CustomPacketPayload {

    public static final Type<RequestConfigureGarrisonPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "request_configure_garrison"));

    public static final StreamCodec<ByteBuf, RequestConfigureGarrisonPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), RequestConfigureGarrisonPayload::signPos,
            RequestConfigureGarrisonPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
