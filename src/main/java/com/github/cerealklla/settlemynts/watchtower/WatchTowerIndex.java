package com.github.cerealklla.settlemynts.watchtower;

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
 * Save-wide index from a Watch Tower plot's own {@code plotId} to its Beacon's current
 * {@link GlobalPos} -- mirrors {@code guardhouse.GuardhouseIndex} exactly. One Beacon per plot.
 */
public final class WatchTowerIndex extends SavedData {

    public static final SavedDataType<WatchTowerIndex> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "watch_tower_index"),
            WatchTowerIndex::new,
            codec());

    private final Map<UUID, GlobalPos> beacons;

    WatchTowerIndex() {
        this(new HashMap<>());
    }

    private WatchTowerIndex(Map<UUID, GlobalPos> beacons) {
        this.beacons = beacons;
    }

    private record Entry(UUID plotId, GlobalPos pos) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("plot_id").forGetter(Entry::plotId),
                GlobalPos.CODEC.fieldOf("pos").forGetter(Entry::pos)
        ).apply(i, Entry::new));
    }

    private static Codec<WatchTowerIndex> codec() {
        return Codec.list(Entry.CODEC).xmap(
                entries -> {
                    Map<UUID, GlobalPos> map = new HashMap<>();
                    for (Entry entry : entries) {
                        map.put(entry.plotId(), entry.pos());
                    }
                    return new WatchTowerIndex(map);
                },
                data -> {
                    List<Entry> entries = new ArrayList<>(data.beacons.size());
                    data.beacons.forEach((plotId, pos) -> entries.add(new Entry(plotId, pos)));
                    return entries;
                });
    }

    public static WatchTowerIndex get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    public void put(UUID plotId, GlobalPos pos) {
        beacons.put(plotId, pos);
        setDirty();
    }

    public Optional<GlobalPos> get(UUID plotId) {
        return Optional.ofNullable(beacons.get(plotId));
    }
}
