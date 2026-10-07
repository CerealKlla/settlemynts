package com.github.cerealklla.settlemynts.roadway;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client-to-server: "Get Roadway Stake" on a finalized settlement's Town Hall Core screen. */
public record RequestRoadwayStakePayload(int coreEntityId) implements CustomPacketPayload {

    public static final Type<RequestRoadwayStakePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "request_roadway_stake"));

    public static final StreamCodec<ByteBuf, RequestRoadwayStakePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, RequestRoadwayStakePayload::coreEntityId,
            RequestRoadwayStakePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
