package com.github.cerealklla.settlemynts.plotsign;

import java.util.List;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server-to-client: the full, current set of entity ids this specific player should see a personal
 * "!" head marker above (2026-10-11 user spec, see {@code SettlemyntsMod#anchorHasUrgentPersonalBusiness}'s
 * own doc for exactly what qualifies) -- always a full replacement list, not a diff, sent by {@code
 * PlotShopProximityTicker} only when the set actually changes. Genuinely per-player: two different
 * players standing next to the same NPC can receive two different payloads, one with its entity id
 * and one without, since "ready to turn in"/"needs talk" is personal quest state -- this is exactly
 * why it can't ride the shared vanilla nameplate (CustomName) the way the public "?" marker does; see
 * {@code client.PersonalQuestMarkerRenderer}'s own doc for the client-side rendering half.
 */
public record PersonalQuestMarkerPayload(List<Integer> entityIds) implements CustomPacketPayload {

    public static final Type<PersonalQuestMarkerPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "personal_quest_marker"));

    public static final StreamCodec<ByteBuf, PersonalQuestMarkerPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), PersonalQuestMarkerPayload::entityIds,
            PersonalQuestMarkerPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
