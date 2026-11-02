package com.github.cerealklla.settlemynts.murmur;

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
 * Save-wide, chunk-independent index from a Murmur Tower's plot id to its last-known real-world
 * position -- mirrors {@code founding.SettlementCoreIndex}/{@code watchtower.WatchTowerIndex}
 * exactly, but with {@link #all} exposed (unlike {@code WatchTowerIndex}) since {@code
 * api.Settlemynts#findNearestMurmurSignal} needs to scan every known tower, not look one up by id.
 */
public final class MurmurTowerIndex extends SavedData {

    public static final SavedDataType<MurmurTowerIndex> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "murmur_tower_index"),
            MurmurTowerIndex::new,
            codec());

    private final Map<UUID, GlobalPos> towers;

    MurmurTowerIndex() {
        this(new HashMap<>());
    }

    private MurmurTowerIndex(Map<UUID, GlobalPos> towers) {
        this.towers = towers;
    }

    private record Entry(UUID plotId, GlobalPos pos) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("plot_id").forGetter(Entry::plotId),
                GlobalPos.CODEC.fieldOf("pos").forGetter(Entry::pos)
        ).apply(i, Entry::new));
    }

    private static Codec<MurmurTowerIndex> codec() {
        return Codec.list(Entry.CODEC).xmap(
                entries -> {
                    Map<UUID, GlobalPos> map = new HashMap<>();
                    for (Entry entry : entries) {
                        map.put(entry.plotId(), entry.pos());
                    }
                    return new MurmurTowerIndex(map);
                },
                data -> {
                    List<Entry> entries = new ArrayList<>(data.towers.size());
                    data.towers.forEach((plotId, pos) -> entries.add(new Entry(plotId, pos)));
                    return entries;
                });
    }

    public static MurmurTowerIndex get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    public void put(UUID plotId, GlobalPos pos) {
        towers.put(plotId, pos);
        setDirty();
    }

    public void remove(UUID plotId) {
        towers.remove(plotId);
        setDirty();
    }

    public Optional<GlobalPos> get(UUID plotId) {
        return Optional.ofNullable(towers.get(plotId));
    }

    public Map<UUID, GlobalPos> all() {
        return Map.copyOf(towers);
    }
}
