package com.github.cerealklla.settlemynts.plotsign;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: "ask the shop what it needs" -- the hotkey/right-click chat interaction on a
 * Shop-eligible Plot Config Sign or NPC, mirroring {@link RequestShopPayload}'s own anchor
 * resolution. Public -- any player can ask, not just the plot's own owner (see {@code
 * zone.ShopWishlist}'s own doc: "public wishlist" was the explicit ask). Server replies with {@link
 * OpenShopWishlistPayload} and also grants a temporary premium-sell offer for everything listed, see
 * {@code zone.WishlistOfferTracker}.
 */
public record RequestShopWishlistPayload(ShopAnchor anchor) implements CustomPacketPayload {

    public static final Type<RequestShopWishlistPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "request_shop_wishlist"));

    public static final StreamCodec<ByteBuf, RequestShopWishlistPayload> STREAM_CODEC = StreamCodec.composite(
            ShopAnchor.STREAM_CODEC, RequestShopWishlistPayload::anchor,
            RequestShopWishlistPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
