package com.github.cerealklla.settlemynts.plotsign;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: "Accept Quest" / "Turn In" on {@code client.QuestOfferScreen} (2026-10-10,
 * reached through the "Press H" chat menu's "I've got a job that needs doing" button) -- runs the
 * same accept-or-deliver mutation a shift+right-click on the quest giver itself triggers (see
 * {@code bridge.MyssionsQuestBridge#interact}). Server replies with a fresh {@link
 * OpenShopWishlistPayload} so the chat menu reflects the new state.
 */
public record AcceptOrTurnInQuestPayload(ShopAnchor anchor) implements CustomPacketPayload {

    public static final Type<AcceptOrTurnInQuestPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "accept_or_turn_in_quest"));

    public static final StreamCodec<ByteBuf, AcceptOrTurnInQuestPayload> STREAM_CODEC = StreamCodec.composite(
            ShopAnchor.STREAM_CODEC, AcceptOrTurnInQuestPayload::anchor,
            AcceptOrTurnInQuestPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
