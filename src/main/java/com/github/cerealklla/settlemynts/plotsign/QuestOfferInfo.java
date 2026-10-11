package com.github.cerealklla.settlemynts.plotsign;

import io.netty.buffer.ByteBuf;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

/**
 * A quest-giver's current offer/status, surfaced inside {@code client.WishlistScreen} (2026-10-10,
 * explicit user request: "these quests should show up in the Press H menu for the NPCs"). This
 * mod's own network-safe DTO for Myssions' {@code quest.QuestGiverPreview} -- never reference that
 * (optional-dependency) type from an always-loaded payload class; {@code
 * bridge.MyssionsQuestBridge} translates one into the other at the one call site that's already
 * gated behind {@code ModList.get().isLoaded("myssions")}.
 */
public record QuestOfferInfo(Identifier questId, String questName, String blurb, QuestAction action) {

    public enum QuestAction {
        /** Shown, but nothing to click yet (still working on a delivery). */
        NONE,
        /** A fresh board offer -- clicking assigns it. */
        ACCEPT,
        /** An in-progress quest with enough proof on hand -- clicking turns it in. */
        TURN_IN
    }

    public static final Codec<QuestOfferInfo> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("quest_id").forGetter(QuestOfferInfo::questId),
            Codec.STRING.fieldOf("quest_name").forGetter(QuestOfferInfo::questName),
            Codec.STRING.fieldOf("blurb").forGetter(QuestOfferInfo::blurb),
            Codec.STRING.xmap(QuestAction::valueOf, Enum::name).fieldOf("action").forGetter(QuestOfferInfo::action)
    ).apply(i, QuestOfferInfo::new));

    public static final StreamCodec<ByteBuf, QuestOfferInfo> STREAM_CODEC = StreamCodec.composite(
            Identifier.STREAM_CODEC, QuestOfferInfo::questId,
            ByteBufCodecs.STRING_UTF8, QuestOfferInfo::questName,
            ByteBufCodecs.STRING_UTF8, QuestOfferInfo::blurb,
            ByteBufCodecs.STRING_UTF8.map(QuestAction::valueOf, Enum::name), QuestOfferInfo::action,
            QuestOfferInfo::new);
}
