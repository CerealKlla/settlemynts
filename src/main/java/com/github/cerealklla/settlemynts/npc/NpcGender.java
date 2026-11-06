package com.github.cerealklla.settlemynts.npc;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

import com.mojang.serialization.Codec;

/**
 * An NPC's gender -- added 2026-11-05 alongside {@link NameBank}'s male/female given-name split and
 * Audyble's per-gender Kokoro voice config, so an NPC's name/voice/visual all agree with each other
 * instead of each being independently hardcoded to "male" (which is all this suite had until today).
 * Rolled once per birth ({@link #roll()}), then persisted on {@link NpcIdentity} for the NPC's whole
 * life -- never re-rolled.
 *
 * <p>Every already-persisted {@link NpcIdentity} from before this field existed decodes as {@link
 * #MALE} (the codec's default, see {@link NpcIdentity#CODEC}) -- not a migration, just matching their
 * current, already-shipped male name/voice/visual exactly, so nothing about an existing NPC changes.
 */
public enum NpcGender {
    MALE, FEMALE;

    public static final Codec<NpcGender> CODEC = Codec.STRING.xmap(
            s -> NpcGender.valueOf(s.toUpperCase(Locale.ROOT)), Enum::name);

    public static NpcGender roll() {
        return ThreadLocalRandom.current().nextBoolean() ? MALE : FEMALE;
    }
}
