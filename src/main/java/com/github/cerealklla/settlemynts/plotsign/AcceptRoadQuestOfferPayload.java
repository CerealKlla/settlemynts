package com.github.cerealklla.settlemynts.plotsign;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: "Accept Quest" on {@code client.RoadQuestAcceptScreen}, reached through the
 * "Press H" chat menu's quest list -- added 2026-10-13, the sibling of {@link
 * AcceptBoardOfferPayload} for a not-yet-accepted Establish Highway offer ({@code
 * plotsign.QuestMenuInfo.RoadQuestOffer}), which can't be identified by a {@code questId}+plotId
 * board lookup since it was never posted through {@code quest.IssuerQuestBoard} at all. The server
 * re-resolves this Town Hall's own settlement placeId from {@code anchor} itself (never trusts a
 * client-supplied origin), same discipline as every other sign action.
 */
public record AcceptRoadQuestOfferPayload(ShopAnchor anchor, long destinationPlaceId) implements CustomPacketPayload {

    public static final Type<AcceptRoadQuestOfferPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "accept_road_quest_offer"));

    public static final StreamCodec<ByteBuf, AcceptRoadQuestOfferPayload> STREAM_CODEC = StreamCodec.composite(
            ShopAnchor.STREAM_CODEC, AcceptRoadQuestOfferPayload::anchor,
            ByteBufCodecs.VAR_LONG, AcceptRoadQuestOfferPayload::destinationPlaceId,
            AcceptRoadQuestOfferPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
