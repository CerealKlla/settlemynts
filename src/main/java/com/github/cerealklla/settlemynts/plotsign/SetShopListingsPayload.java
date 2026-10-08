package com.github.cerealklla.settlemynts.plotsign;

import java.util.List;

import io.netty.buffer.ByteBuf;

import com.mojang.serialization.Codec;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server, Manage Shop only: "Save Changes" on {@code client.ManageShopScreen} (2026-10-08,
 * replacing {@code AddListingFromHeldItemPayload}/{@code AdjustListingPayload}'s held-item-and-click-
 * spam flow entirely). One batch covering every row the owner saw, not just the ones they actually
 * edited -- simplest correct option, and idempotent (re-setting an unchanged price is a no-op) so
 * there's no need to diff against the original server state client-side first.
 */
public record SetShopListingsPayload(BlockPos signPos, List<ShopListingUpdate> updates) implements CustomPacketPayload {

    public static final Type<SetShopListingsPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "set_shop_listings"));

    private static final Codec<List<ShopListingUpdate>> UPDATES_CODEC = ShopListingUpdate.CODEC.listOf();

    public static final StreamCodec<ByteBuf, SetShopListingsPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), SetShopListingsPayload::signPos,
            ByteBufCodecs.fromCodec(UPDATES_CODEC), SetShopListingsPayload::updates,
            SetShopListingsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
