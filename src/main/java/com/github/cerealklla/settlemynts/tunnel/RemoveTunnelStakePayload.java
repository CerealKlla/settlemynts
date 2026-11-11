package com.github.cerealklla.settlemynts.tunnel;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client-to-server: "Remove" on {@code client.TunnelStakeScreen}. No item handed back, same reasoning as every other ghost stake in this mod. */
public record RemoveTunnelStakePayload(int stakeEntityId) implements CustomPacketPayload {

    public static final Type<RemoveTunnelStakePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "remove_tunnel_stake"));

    public static final StreamCodec<ByteBuf, RemoveTunnelStakePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, RemoveTunnelStakePayload::stakeEntityId,
            RemoveTunnelStakePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
