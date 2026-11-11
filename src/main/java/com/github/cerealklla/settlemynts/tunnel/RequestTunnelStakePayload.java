package com.github.cerealklla.settlemynts.tunnel;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client-to-server: "Get Tunnel Stakes" on a finalized settlement's Town Hall Core screen. */
public record RequestTunnelStakePayload(int coreEntityId) implements CustomPacketPayload {

    public static final Type<RequestTunnelStakePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "request_tunnel_stake"));

    public static final StreamCodec<ByteBuf, RequestTunnelStakePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, RequestTunnelStakePayload::coreEntityId,
            RequestTunnelStakePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
