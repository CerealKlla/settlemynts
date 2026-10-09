package com.github.cerealklla.settlemynts.plotsign;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: a "Sell N" click on {@code client.ShopScreen} (2026-10-08, the reverse of {@link
 * BuyFromShopPayload}). {@code resourceKey}/{@code isTag} identify which existing listing is being
 * sold back (a shop only ever buys back something it already sells, see {@code
 * api.Yconomics#sellToShop}'s own doc) -- the concrete item actually sold is resolved server-side
 * from the seller's own held stack rather than trusting anything sent from the client.
 */
public record SellToShopPayload(ShopAnchor anchor, Identifier resourceKey, boolean isTag, int quantity) implements CustomPacketPayload {

    public static final Type<SellToShopPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "sell_to_shop"));

    public static final StreamCodec<ByteBuf, SellToShopPayload> STREAM_CODEC = StreamCodec.composite(
            ShopAnchor.STREAM_CODEC, SellToShopPayload::anchor,
            ByteBufCodecs.fromCodec(Identifier.CODEC), SellToShopPayload::resourceKey,
            ByteBufCodecs.BOOL, SellToShopPayload::isTag,
            ByteBufCodecs.VAR_INT, SellToShopPayload::quantity,
            SellToShopPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
