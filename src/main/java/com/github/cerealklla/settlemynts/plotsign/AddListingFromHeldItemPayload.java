package com.github.cerealklla.settlemynts.plotsign;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server, Manage Shop only: "Add Listing (Held Item)" on {@code client.ShopScreen}. The
 * resource is whatever the player is currently holding in their main hand, read server-side (not
 * sent from the client) -- avoids needing any item-picker UI. Starts at a price of 1/unit; the
 * owner adjusts from there via {@link AdjustListingPayload}'s price-delta buttons. A no-op (with a
 * chat message) if the main hand is empty or the resource is already listed.
 */
public record AddListingFromHeldItemPayload(BlockPos signPos) implements CustomPacketPayload {

    public static final Type<AddListingFromHeldItemPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "add_listing_from_held_item"));

    public static final StreamCodec<ByteBuf, AddListingFromHeldItemPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), AddListingFromHeldItemPayload::signPos,
            AddListingFromHeldItemPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
