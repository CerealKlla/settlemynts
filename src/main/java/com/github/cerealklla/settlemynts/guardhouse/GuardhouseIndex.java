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
 * Save-wide index from a Guardhouse plot's own {@code plotId} to its Guardhouse structure's current
 * {@link GlobalPos} -- mirrors {@code plotsign.PlotConfigSignIndex} exactly. One Guardhouse per
 * plot, so {@code plotId} is the key directly.
 */
public final class GuardhouseIndex extends SavedData {

    public static final SavedDataType<GuardhouseIndex> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "guardhouse_index"),
            GuardhouseIndex::new,
            codec());

    private final Map<UUID, GlobalPos> guardhouses;

    GuardhouseIndex() {
        this(new HashMap<>());
    }

    private GuardhouseIndex(Map<UUID, GlobalPos> guardhouses) {
        this.guardhouses = guardhouses;
    }

    private record Entry(UUID plotId, GlobalPos pos) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("plot_id").forGetter(Entry::plotId),
                GlobalPos.CODEC.fieldOf("pos").forGetter(Entry::pos)
        ).apply(i, Entry::new));
    }

    private static Codec<GuardhouseIndex> codec() {
        return Codec.list(Entry.CODEC).xmap(
                entries -> {
                    Map<UUID, GlobalPos> map = new HashMap<>();
                    for (Entry entry : entries) {
                        map.put(entry.plotId(), entry.pos());
                    }
                    return new GuardhouseIndex(map);
                },
                data -> {
                    List<Entry> entries = new ArrayList<>(data.guardhouses.size());
                    data.guardhouses.forEach((plotId, pos) -> entries.add(new Entry(plotId, pos)));
                    return entries;
                });
    }

    public static GuardhouseIndex get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    public void put(UUID plotId, GlobalPos pos) {
        guardhouses.put(plotId, pos);
        setDirty();
    }

    public Optional<GlobalPos> get(UUID plotId) {
        return Optional.ofNullable(guardhouses.get(plotId));
    }

    /** Read-only snapshot of every known Guardhouse -- used by {@code GuardSpawnTicker} to discover all of them without a world-wide entity query (block entities, unlike {@code GhostTownHallCoreEntity}, aren't queryable that way). */
    public Map<UUID, GlobalPos> all() {
        return Map.copyOf(guardhouses);
    }
}
