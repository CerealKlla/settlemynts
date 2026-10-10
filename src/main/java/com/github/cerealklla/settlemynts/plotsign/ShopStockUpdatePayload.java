package com.github.cerealklla.settlemynts.plotsign;

import java.util.List;

import io.netty.buffer.ByteBuf;

import com.mojang.serialization.Codec;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server-to-client: the "Shop Stock" column on an already-open {@code client.ShopScreen} (buy mode)
 * just changed (real report, 2026-10-10: "When buying/selling from a store the shop stock number is
 * not changing" -- same root cause as {@link ShopGoldUpdatePayload}'s own doc describes for the gold
 * totals: {@link OpenShopPayload#listings()} is a one-time snapshot from whenever the screen opened,
 * and that screen never re-opens itself after a Buy/Sell). Sent right after a successful {@code
 * BuyFromShopPayload}/{@code SellToShopPayload} completes, carrying a freshly recomputed listings
 * list (same prices, refreshed stock) built the same way {@code SettlemyntsMod#handleRequestShop}
 * does. Deliberately its own payload rather than reusing {@link ShopGoldUpdatePayload} -- that one
 * is sent unconditionally for both Buy and Sell mode shop anchors (NPCs can sell without a full
 * listing table), while this one only ever matters for the buy-mode listing table.
 */
public record ShopStockUpdatePayload(List<ShopListingEntry> listings) implements CustomPacketPayload {

    public static final Type<ShopStockUpdatePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "shop_stock_update"));

    private static final Codec<List<ShopListingEntry>> LISTINGS_CODEC = ShopListingEntry.CODEC.listOf();

    public static final StreamCodec<ByteBuf, ShopStockUpdatePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(LISTINGS_CODEC), ShopStockUpdatePayload::listings,
            ShopStockUpdatePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
