package com.github.cerealklla.settlemynts.founding;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: sets a settlement's color (originally backlog item 10's Capital-City-only
 * payload, widened the same day, 2026-11-13, for backlog item 11's regular-settlement Tier 2+
 * gate) from {@code client.ColorAndBannerScreen}'s color cycle button -- sends immediately on
 * click, same "no separate confirm step" convention {@code FoundingScreen}'s own boundary-
 * visibility toggle uses, not the free-text tax/tags box's confirm-button shape. {@code colorName}
 * is {@code DyeColor#getName()}, parsed server-side via {@code DyeColor#byName}.
 */
public record SetSettlementColorPayload(int coreEntityId, String colorName) implements CustomPacketPayload {

    public static final Type<SetSettlementColorPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "set_settlement_color"));

    public static final StreamCodec<ByteBuf, SetSettlementColorPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetSettlementColorPayload::coreEntityId,
            ByteBufCodecs.STRING_UTF8, SetSettlementColorPayload::colorName,
            SetSettlementColorPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
