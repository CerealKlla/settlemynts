package com.github.cerealklla.settlemynts.founding;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: sets a settlement's required Blueprint tags from {@code client.FoundingScreen}
 * (backlog item 5, added 2026-11-13) -- {@code tags} is the raw comma-separated Mayor input, parsed
 * server-side via {@code zone.RequiredTags#parse}, never pre-parsed client-side (same convention
 * Blueprynts' own {@code SaveBlueprintPayload} uses for its tags field). **Mayor (founder) only**,
 * unlike {@link SetTaxRatePayload}/{@link SetSettlementNamePayload} which any Town Planner may set
 * -- the backlog item explicitly names "the Mayor," so this is gated more narrowly.
 */
public record SetRequiredTagsPayload(int coreEntityId, String tags) implements CustomPacketPayload {

    public static final Type<SetRequiredTagsPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "set_required_tags"));

    public static final StreamCodec<ByteBuf, SetRequiredTagsPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetRequiredTagsPayload::coreEntityId,
            ByteBufCodecs.STRING_UTF8, SetRequiredTagsPayload::tags,
            SetRequiredTagsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
