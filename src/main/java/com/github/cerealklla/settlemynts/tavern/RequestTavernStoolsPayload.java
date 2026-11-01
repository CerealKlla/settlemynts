package com.github.cerealklla.settlemynts.tavern;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: "Get Tavern Stools" button on {@code plotsign.client.TavernTab}. Direct mirror
 * of {@code guardhouse.RequestGuardKitStandsPayload} -- see {@code SettlemyntsMod#requestTavernStools}.
 */
public record RequestTavernStoolsPayload(BlockPos signPos) implements CustomPacketPayload {

    public static final Type<RequestTavernStoolsPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "request_tavern_stools"));

    public static final StreamCodec<ByteBuf, RequestTavernStoolsPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), RequestTavernStoolsPayload::signPos,
            RequestTavernStoolsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
