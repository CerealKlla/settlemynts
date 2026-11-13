package com.github.cerealklla.settlemynts.founding;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: the color/banner menu button on {@code client.FoundingScreen} (originally
 * added 2026-11-13 as backlog item 10's Capital-City-only "Capital City Keep..." button; widened
 * the same day for backlog item 11 to also serve a regular settlement's "Settlement Hall..."
 * button -- same request, same screen either way, just different Tier gates server-side). Same
 * shape as {@link RequestDefenseSpendingPayload}.
 */
public record RequestColorAndBannerScreenPayload(int coreEntityId) implements CustomPacketPayload {

    public static final Type<RequestColorAndBannerScreenPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "request_color_and_banner_screen"));

    public static final StreamCodec<ByteBuf, RequestColorAndBannerScreenPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, RequestColorAndBannerScreenPayload::coreEntityId,
            RequestColorAndBannerScreenPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
