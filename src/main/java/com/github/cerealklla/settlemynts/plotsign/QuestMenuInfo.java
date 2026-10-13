package com.github.cerealklla.settlemynts.plotsign;

import java.util.List;
import java.util.UUID;

import io.netty.buffer.ByteBuf;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

/**
 * This NPC's own quest menu -- the player's own active quests from its pool, plus any unclaimed
 * board offer -- surfaced inside {@code client.ChatMenuScreen}'s "I've got a job that needs doing"
 * button (2026-10-10, explicit user spec: "you should be able to select your active quest by name,
 * and then in a sub-menu have the option to complete the quest"). This mod's own network-safe DTO
 * for Myssions' {@code quest.QuestGiverMenu} -- never reference that (optional-dependency) type
 * from an always-loaded payload class; {@code bridge.MyssionsQuestBridge} translates one into the
 * other at the one call site that's already gated behind {@code
 * ModList.get().isLoaded("myssions")}.
 */
public record QuestMenuInfo(List<ActiveEntry> activeQuests, List<NewOffer> newOffers, List<RoadQuestOffer> roadQuestOffers) {

    public static final QuestMenuInfo EMPTY = new QuestMenuInfo(List.of(), List.of(), List.of());

    /**
     * A not-yet-accepted Establish Highway offer this Town Hall has posted out (added 2026-10-13) --
     * distinct from {@link NewOffer} since accepting it needs a settlement-pair placeId, not a
     * {@code questId}+{@code plotId} board-offer lookup (see {@code bridge.MyssionsQuestBridge
     * #acceptRoadQuestOffer}'s own doc). {@code destinationName} is already resolved server-side
     * (Cartographyr display text), never computed client-side.
     */
    public record RoadQuestOffer(long destinationPlaceId, String destinationName) {

        public static final StreamCodec<ByteBuf, RoadQuestOffer> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, RoadQuestOffer::destinationPlaceId,
                ByteBufCodecs.STRING_UTF8, RoadQuestOffer::destinationName,
                RoadQuestOffer::new);
    }

    /** One of the player's own active (in-progress) quest instances from this NPC's pool -- {@code instanceId} is Myssions' own hidden per-acceptance UUID, never shown directly, only round-tripped. */
    public record ActiveEntry(UUID instanceId, String questName, boolean readyToComplete) {

        public static final StreamCodec<ByteBuf, ActiveEntry> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, ActiveEntry::instanceId,
                ByteBufCodecs.STRING_UTF8, ActiveEntry::questName,
                ByteBufCodecs.BOOL, ActiveEntry::readyToComplete,
                ActiveEntry::new);
    }

    /** One of the plot's current unclaimed board offers, filtered to ones the player doesn't already have a record of. */
    public record NewOffer(Identifier questId, String questName, String description) {

        public static final StreamCodec<ByteBuf, NewOffer> STREAM_CODEC = StreamCodec.composite(
                Identifier.STREAM_CODEC, NewOffer::questId,
                ByteBufCodecs.STRING_UTF8, NewOffer::questName,
                ByteBufCodecs.STRING_UTF8, NewOffer::description,
                NewOffer::new);
    }

    public static final StreamCodec<ByteBuf, QuestMenuInfo> STREAM_CODEC = StreamCodec.composite(
            ActiveEntry.STREAM_CODEC.apply(ByteBufCodecs.list()), QuestMenuInfo::activeQuests,
            NewOffer.STREAM_CODEC.apply(ByteBufCodecs.list()), QuestMenuInfo::newOffers,
            RoadQuestOffer.STREAM_CODEC.apply(ByteBufCodecs.list()), QuestMenuInfo::roadQuestOffers,
            QuestMenuInfo::new);
}
