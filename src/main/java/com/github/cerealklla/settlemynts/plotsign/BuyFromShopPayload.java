package com.github.cerealklla.settlemynts.plotsign;

import java.util.Optional;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: a "Buy N" click on {@code client.ShopScreen}. Server charges the buyer's own
 * Gold Nugget balance (loose inventory, then Coin Purse) only for however much was actually filled.
 * {@code customName} (added 2026-10-10, per-quality Shop listings) identifies one exact crafted-
 * quality variant (e.g. Lyfe's baked {@code "[3.75] (T5) - Bread"}) -- empty for a plain listing.
 */
public record BuyFromShopPayload(ShopAnchor anchor, Identifier resourceKey, boolean isTag, Optional<String> customName, int quantity) implements CustomPacketPayload {

    public static final Type<BuyFromShopPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "buy_from_shop"));

    public static final StreamCodec<ByteBuf, BuyFromShopPayload> STREAM_CODEC = StreamCodec.composite(
            ShopAnchor.STREAM_CODEC, BuyFromShopPayload::anchor,
            ByteBufCodecs.fromCodec(Identifier.CODEC), BuyFromShopPayload::resourceKey,
            ByteBufCodecs.BOOL, BuyFromShopPayload::isTag,
            ByteBufCodecs.optional(ByteBufCodecs.STRING_UTF8), BuyFromShopPayload::customName,
            ByteBufCodecs.VAR_INT, BuyFromShopPayload::quantity,
            BuyFromShopPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
