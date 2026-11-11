package com.github.cerealklla.settlemynts.tunnel;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server-to-client: opens {@code client.TunnelStakeScreen} -- see {@code TunnelStakeEntity#interact}. */
public record OpenTunnelStakeScreenPayload(int stakeEntityId, boolean paired) implements CustomPacketPayload {

    public static final Type<OpenTunnelStakeScreenPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "open_tunnel_stake_screen"));

    public static final StreamCodec<ByteBuf, OpenTunnelStakeScreenPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, OpenTunnelStakeScreenPayload::stakeEntityId,
            ByteBufCodecs.BOOL, OpenTunnelStakeScreenPayload::paired,
            OpenTunnelStakeScreenPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
