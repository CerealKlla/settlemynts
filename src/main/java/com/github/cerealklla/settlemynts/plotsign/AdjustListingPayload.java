package com.github.cerealklla.settlemynts.plotsign;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server, Manage Shop only: a price +/- click, or "Add Listing (Held Item)"/"Remove" on
 * {@code client.ShopScreen}. {@code priceDelta} of {@code 0} with a resource not currently listed
 * means "add it" (starting price 1); {@code remove = true} deletes the listing outright regardless
 * of {@code priceDelta}. Canonical-managed-only check happens server-side via {@code
 * zone.PlotPermissions#canManage}, same as every other Manage action in this mod.
 */
public record AdjustListingPayload(BlockPos signPos, Identifier resourceKey, boolean isTag, int priceDelta, boolean remove) implements CustomPacketPayload {

    public static final Type<AdjustListingPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "adjust_listing"));

    public static final StreamCodec<ByteBuf, AdjustListingPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), AdjustListingPayload::signPos,
            ByteBufCodecs.fromCodec(Identifier.CODEC), AdjustListingPayload::resourceKey,
            ByteBufCodecs.BOOL, AdjustListingPayload::isTag,
            ByteBufCodecs.VAR_INT, AdjustListingPayload::priceDelta,
            ByteBufCodecs.BOOL, AdjustListingPayload::remove,
            AdjustListingPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
