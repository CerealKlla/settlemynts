package com.github.cerealklla.settlemynts.tunnel;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client-to-server: "Finalize" on {@code client.TunnelStakeScreen} -- converts a paired Tunnel Stake pair into a real Tunnel door pair. */
public record FinalizeTunnelStakePayload(int stakeEntityId) implements CustomPacketPayload {

    public static final Type<FinalizeTunnelStakePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "finalize_tunnel_stake"));

    public static final StreamCodec<ByteBuf, FinalizeTunnelStakePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, FinalizeTunnelStakePayload::stakeEntityId,
            FinalizeTunnelStakePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
