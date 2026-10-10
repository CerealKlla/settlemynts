package com.github.cerealklla.settlemynts.plotsign;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: "Sell" on one {@code client.WishlistScreen} row -- a direct, premium-priced sale
 * against the shop's own Planned Inventory deficit, only valid while {@code
 * zone.WishlistOfferTracker#isActive} for this exact player/plot/resource (i.e. they asked within
 * the last {@code zone.ShopWishlist#OFFER_WINDOW_TICKS}). Server re-resolves the real deficit/price
 * at sale time rather than trusting anything the client remembers from when it asked.
 */
public record SellToShopWishlistPayload(ShopAnchor anchor, Identifier resourceKey, boolean isTag, int quantity) implements CustomPacketPayload {

    public static final Type<SellToShopWishlistPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "sell_to_shop_wishlist"));

    public static final StreamCodec<ByteBuf, SellToShopWishlistPayload> STREAM_CODEC = StreamCodec.composite(
            ShopAnchor.STREAM_CODEC, SellToShopWishlistPayload::anchor,
            ByteBufCodecs.fromCodec(Identifier.CODEC), SellToShopWishlistPayload::resourceKey,
            ByteBufCodecs.BOOL, SellToShopWishlistPayload::isTag,
            ByteBufCodecs.VAR_INT, SellToShopWishlistPayload::quantity,
            SellToShopWishlistPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
