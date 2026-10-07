package com.github.cerealklla.settlemynts.plotsign;

import java.util.List;
import java.util.UUID;

import io.netty.buffer.ByteBuf;

import com.mojang.serialization.Codec;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server-to-client: opens {@code client.ShopScreen} (design doc Section 14a, 2026-10-05) --
 * replaces the old chat-message-only {@code PlotShopPlaceholderPayload} reply. {@code manage}
 * mirrors the request -- the same screen renders Buy or Manage controls depending on it.
 */
public record OpenShopPayload(BlockPos signPos, UUID plotId, boolean manage, List<ShopListingEntry> listings) implements CustomPacketPayload {

    public static final Type<OpenShopPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "open_shop"));

    private static final Codec<List<ShopListingEntry>> LISTINGS_CODEC = ShopListingEntry.CODEC.listOf();

    public static final StreamCodec<ByteBuf, OpenShopPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), OpenShopPayload::signPos,
            ByteBufCodecs.fromCodec(UUIDUtil.CODEC), OpenShopPayload::plotId,
            ByteBufCodecs.BOOL, OpenShopPayload::manage,
            ByteBufCodecs.fromCodec(LISTINGS_CODEC), OpenShopPayload::listings,
            OpenShopPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
