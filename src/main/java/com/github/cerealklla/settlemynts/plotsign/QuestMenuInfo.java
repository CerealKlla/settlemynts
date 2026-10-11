package com.github.cerealklla.settlemynts.plotsign;

import java.util.List;
import java.util.Optional;
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
public record QuestMenuInfo(List<ActiveEntry> activeQuests, Optional<NewOffer> newOffer) {

    public static final QuestMenuInfo EMPTY = new QuestMenuInfo(List.of(), Optional.empty());

    /** One of the player's own active (in-progress) quest instances from this NPC's pool -- {@code instanceId} is Myssions' own hidden per-acceptance UUID, never shown directly, only round-tripped. */
    public record ActiveEntry(UUID instanceId, String questName, boolean readyToComplete) {

        public static final StreamCodec<ByteBuf, ActiveEntry> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, ActiveEntry::instanceId,
                ByteBufCodecs.STRING_UTF8, ActiveEntry::questName,
                ByteBufCodecs.BOOL, ActiveEntry::readyToComplete,
                ActiveEntry::new);
    }

    /** The plot's current unclaimed board offer, if any and if the player doesn't already have a record of it. */
    public record NewOffer(Identifier questId, String questName, String description) {

        public static final StreamCodec<ByteBuf, NewOffer> STREAM_CODEC = StreamCodec.composite(
                Identifier.STREAM_CODEC, NewOffer::questId,
                ByteBufCodecs.STRING_UTF8, NewOffer::questName,
                ByteBufCodecs.STRING_UTF8, NewOffer::description,
                NewOffer::new);
    }

    public static final StreamCodec<ByteBuf, QuestMenuInfo> STREAM_CODEC = StreamCodec.composite(
            ActiveEntry.STREAM_CODEC.apply(ByteBufCodecs.list()), QuestMenuInfo::activeQuests,
            ByteBufCodecs.optional(NewOffer.STREAM_CODEC), QuestMenuInfo::newOffer,
            QuestMenuInfo::new);
}
