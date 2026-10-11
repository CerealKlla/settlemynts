package com.github.cerealklla.settlemynts.plotsign;

import java.util.UUID;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: "Complete Quest" on {@code client.QuestCompleteScreen} (2026-10-10, explicit
 * user spec: "select your active quest by name, and then in a sub-menu have the option to complete
 * the quest") -- {@code instanceId} is Myssions' own hidden per-acceptance UUID (see {@code
 * QuestMenuInfo.ActiveEntry}'s own doc), round-tripped from the menu the player picked it from.
 * Server replies with a fresh {@link OpenShopWishlistPayload} so the chat menu reflects the new
 * state.
 */
public record CompleteQuestPayload(ShopAnchor anchor, UUID instanceId) implements CustomPacketPayload {

    public static final Type<CompleteQuestPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "complete_quest"));

    public static final StreamCodec<ByteBuf, CompleteQuestPayload> STREAM_CODEC = StreamCodec.composite(
            ShopAnchor.STREAM_CODEC, CompleteQuestPayload::anchor,
            UUIDUtil.STREAM_CODEC, CompleteQuestPayload::instanceId,
            CompleteQuestPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
