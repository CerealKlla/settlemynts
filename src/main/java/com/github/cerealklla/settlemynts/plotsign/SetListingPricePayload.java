package com.github.cerealklla.settlemynts.plotsign;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server, Manage Shop only: "Confirm" on {@code client.EditListingPriceScreen} (2026-10-08,
 * replacing the old +/-1/+/-10 click-spam -- real user feedback: "what if they want to sell a T5
 * sword for 10,000 nuggets? You want them to spam click +10 1000 times?"). Sets {@code resourceKey}'s
 * listing to exactly {@code newPrice} rather than nudging it by a delta. Same
 * {@code zone.PlotPermissions#canManage} check as every other Manage action; the floor/never-equal
 * invariant is enforced regardless of this payload, inside {@code shop.ShopListing}'s own constructor
 * on the Yconomics side -- this screen never needs to duplicate that validation.
 */
public record SetListingPricePayload(BlockPos signPos, Identifier resourceKey, boolean isTag, int newPrice) implements CustomPacketPayload {

    public static final Type<SetListingPricePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "set_listing_price"));

    public static final StreamCodec<ByteBuf, SetListingPricePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), SetListingPricePayload::signPos,
            ByteBufCodecs.fromCodec(Identifier.CODEC), SetListingPricePayload::resourceKey,
            ByteBufCodecs.BOOL, SetListingPricePayload::isTag,
            ByteBufCodecs.VAR_INT, SetListingPricePayload::newPrice,
            SetListingPricePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
