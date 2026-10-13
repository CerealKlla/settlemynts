package com.github.cerealklla.settlemynts.plotsign;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: "Create Quest" button on {@code client.EstablishHighwayMapScreen} -- added
 * 2026-10-13. Everything is re-resolved and re-validated server-side from scratch (founder check,
 * 1000 Gold Nuggets in the Town Hall's own boxes, the settlement pair still free) -- {@code
 * destinationPlaceId} is the only real input the client supplies, same "the client only ever picks,
 * the server always re-decides" discipline as every other sign action in this mod.
 */
public record RequestCreateHighwayQuestPayload(BlockPos signPos, long destinationPlaceId) implements CustomPacketPayload {

    public static final Type<RequestCreateHighwayQuestPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "request_create_highway_quest"));

    public static final StreamCodec<ByteBuf, RequestCreateHighwayQuestPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), RequestCreateHighwayQuestPayload::signPos,
            ByteBufCodecs.VAR_LONG, RequestCreateHighwayQuestPayload::destinationPlaceId,
            RequestCreateHighwayQuestPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
