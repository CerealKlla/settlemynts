package com.github.cerealklla.settlemynts.founding;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: sets a Capital City's color (backlog item 10, added 2026-11-13) from {@code
 * client.CapitalCityKeepScreen}'s color cycle button -- sends immediately on click, same
 * "no separate confirm step" convention {@code FoundingScreen}'s own boundary-visibility toggle
 * uses, not the free-text tax/tags box's confirm-button shape. {@code colorName} is {@code
 * DyeColor#getName()}, parsed server-side via {@code DyeColor#byName}.
 */
public record SetCapitalCityColorPayload(int coreEntityId, String colorName) implements CustomPacketPayload {

    public static final Type<SetCapitalCityColorPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "set_capital_city_color"));

    public static final StreamCodec<ByteBuf, SetCapitalCityColorPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetCapitalCityColorPayload::coreEntityId,
            ByteBufCodecs.STRING_UTF8, SetCapitalCityColorPayload::colorName,
            SetCapitalCityColorPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
