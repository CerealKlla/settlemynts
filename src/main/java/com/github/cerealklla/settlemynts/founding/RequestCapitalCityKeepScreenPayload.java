package com.github.cerealklla.settlemynts.founding;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: "Capital City Keep..." button on {@code client.FoundingScreen} (backlog item
 * 10, added 2026-11-13) -- same shape as {@link RequestDefenseSpendingPayload}. Only ever shown/
 * sent when {@code GhostTownHallCoreEntity#isCapitalCity}; the server re-checks that itself too.
 */
public record RequestCapitalCityKeepScreenPayload(int coreEntityId) implements CustomPacketPayload {

    public static final Type<RequestCapitalCityKeepScreenPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "request_capital_city_keep_screen"));

    public static final StreamCodec<ByteBuf, RequestCapitalCityKeepScreenPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, RequestCapitalCityKeepScreenPayload::coreEntityId,
            RequestCapitalCityKeepScreenPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
