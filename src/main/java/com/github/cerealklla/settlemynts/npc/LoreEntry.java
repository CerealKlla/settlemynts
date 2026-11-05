package com.github.cerealklla.settlemynts.npc;

import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * One entry in {@link SettlementLoreStore}'s bulk-pre-generated pool -- moved here from Audyble's own
 * {@code npc.LoreEntry}, 2026-11-05. {@code assignedPlaceId} is a real Cartographyr place id (the same
 * raw {@code long} entity id convention already used suite-wide, e.g. {@code
 * zone.PlotRecord#cartographyrPlotEntityId}) -- {@code Optional.empty()} means this pool entry hasn't
 * been claimed by a real settlement yet. Once assigned, it's permanent -- see {@link
 * SettlementLoreStore#getOrAssign}.
 */
public record LoreEntry(int poolIndex, String text, Optional<Long> assignedPlaceId) {

    public static final Codec<LoreEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("pool_index").forGetter(LoreEntry::poolIndex),
            Codec.STRING.fieldOf("text").forGetter(LoreEntry::text),
            Codec.LONG.optionalFieldOf("assigned_place_id").forGetter(LoreEntry::assignedPlaceId)
    ).apply(i, LoreEntry::new));

    public LoreEntry assignedTo(long placeId) {
        return new LoreEntry(poolIndex, text, Optional.of(placeId));
    }
}
