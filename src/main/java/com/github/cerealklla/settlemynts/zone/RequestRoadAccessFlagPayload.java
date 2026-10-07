package com.github.cerealklla.settlemynts.zone;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: "Get Road Access Flag" on a {@code PlotStakeScreen}. Same "resolve session from
 * the live stake entity, never trust the client for identity" shape as {@link
 * RemovePlotStakePayload}/{@link FinalizePlotPayload}.
 */
public record RequestRoadAccessFlagPayload(int stakeEntityId) implements CustomPacketPayload {

    public static final Type<RequestRoadAccessFlagPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "request_road_access_flag"));

    public static final StreamCodec<ByteBuf, RequestRoadAccessFlagPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, RequestRoadAccessFlagPayload::stakeEntityId,
            RequestRoadAccessFlagPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
