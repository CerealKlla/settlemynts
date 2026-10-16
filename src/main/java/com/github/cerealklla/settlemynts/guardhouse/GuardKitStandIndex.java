package com.github.cerealklla.settlemynts.guardhouse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Save-wide index from a placed {@link GuardKitStandEntity}'s own UUID to its current {@link
 * GlobalPos} -- Guard Kit Stand overhaul, 2026-10-16. Mirrors {@link GuardhouseIndex} exactly,
 * except keyed by the stand's own entity id rather than a plot id, since a plot can hold several
 * stands (up to {@code GuardhouseConstants#capacityForTier}) where it only ever has one Guardhouse.
 * Lets {@code GuardSpawnTicker} and {@code GuardEntity#die} resolve a specific stand without a
 * world-wide entity query (this mod has no generic "find entity by UUID across all loaded chunks"
 * utility, same reason {@link GuardhouseIndex} exists for the block-entity case).
 */
public final class GuardKitStandIndex extends SavedData {

    public static final SavedDataType<GuardKitStandIndex> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "guard_kit_stand_index"),
            GuardKitStandIndex::new,
            codec());

    private final Map<UUID, GlobalPos> stands;

    GuardKitStandIndex() {
        this(new HashMap<>());
    }

    private GuardKitStandIndex(Map<UUID, GlobalPos> stands) {
        this.stands = stands;
    }

    private record Entry(UUID standId, GlobalPos pos) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("stand_id").forGetter(Entry::standId),
                GlobalPos.CODEC.fieldOf("pos").forGetter(Entry::pos)
        ).apply(i, Entry::new));
    }

    private static Codec<GuardKitStandIndex> codec() {
        return Codec.list(Entry.CODEC).xmap(
                entries -> {
                    Map<UUID, GlobalPos> map = new HashMap<>();
                    for (Entry entry : entries) {
                        map.put(entry.standId(), entry.pos());
                    }
                    return new GuardKitStandIndex(map);
                },
                data -> {
                    List<Entry> entries = new ArrayList<>(data.stands.size());
                    data.stands.forEach((standId, pos) -> entries.add(new Entry(standId, pos)));
                    return entries;
                });
    }

    public static GuardKitStandIndex get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    public void put(UUID standId, GlobalPos pos) {
        stands.put(standId, pos);
        setDirty();
    }

    public void remove(UUID standId) {
        if (stands.remove(standId) != null) {
            setDirty();
        }
    }

    public Optional<GlobalPos> get(UUID standId) {
        return Optional.ofNullable(stands.get(standId));
    }

    /** Read-only snapshot of every known Guard Kit Stand -- used by {@code GuardSpawnTicker} to scan all of them without a world-wide entity query. */
    public Map<UUID, GlobalPos> all() {
        return Map.copyOf(stands);
    }
}
