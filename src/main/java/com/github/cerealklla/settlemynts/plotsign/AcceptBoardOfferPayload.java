package com.github.cerealklla.settlemynts.plotsign;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: "Accept Quest" on {@code client.QuestAcceptScreen} (2026-10-10, reached through
 * the "Press H" chat menu's "I've got a job that needs doing" -> quest list -> the board's new
 * offer) -- accepts whatever's currently on this plot's board, same mutation a shift+right-click
 * quick-interact's board-accept branch triggers. Server replies with a fresh {@link
 * OpenShopWishlistPayload} so the chat menu reflects the new state.
 */
public record AcceptBoardOfferPayload(ShopAnchor anchor) implements CustomPacketPayload {

    public static final Type<AcceptBoardOfferPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "accept_board_offer"));

    public static final StreamCodec<ByteBuf, AcceptBoardOfferPayload> STREAM_CODEC = StreamCodec.composite(
            ShopAnchor.STREAM_CODEC, AcceptBoardOfferPayload::anchor,
            AcceptBoardOfferPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
