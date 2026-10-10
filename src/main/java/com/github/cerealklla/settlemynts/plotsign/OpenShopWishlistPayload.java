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
 * Server-to-client: opens (or refreshes, after a {@link SellToShopWishlistPayload} sale) {@code
 * client.WishlistScreen} with this shop's current public deficits -- see {@code zone.ShopWishlist}'s
 * own doc.
 */
public record OpenShopWishlistPayload(ShopAnchor anchor, List<WishlistEntry> entries) implements CustomPacketPayload {

    public static final Type<OpenShopWishlistPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "open_shop_wishlist"));

    private static final Codec<List<WishlistEntry>> ENTRIES_CODEC = WishlistEntry.CODEC.listOf();

    public static final StreamCodec<ByteBuf, OpenShopWishlistPayload> STREAM_CODEC = StreamCodec.composite(
            ShopAnchor.STREAM_CODEC, OpenShopWishlistPayload::anchor,
            ByteBufCodecs.fromCodec(ENTRIES_CODEC), OpenShopWishlistPayload::entries,
            OpenShopWishlistPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
