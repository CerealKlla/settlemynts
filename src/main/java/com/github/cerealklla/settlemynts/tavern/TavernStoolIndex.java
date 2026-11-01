package com.github.cerealklla.settlemynts.tavern;

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
 * Save-wide index from a placed {@link TavernStoolEntity}'s own UUID to its current {@link
 * GlobalPos} -- direct mirror of {@code guardhouse.GuardKitStandIndex}, same reasoning (lets {@code
 * TavernDailySpawnTicker} resolve every known stool without a world-wide entity query).
 */
public final class TavernStoolIndex extends SavedData {

    public static final SavedDataType<TavernStoolIndex> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "tavern_stool_index"),
            TavernStoolIndex::new,
            codec());

    private final Map<UUID, GlobalPos> stools;

    TavernStoolIndex() {
        this(new HashMap<>());
    }

    private TavernStoolIndex(Map<UUID, GlobalPos> stools) {
        this.stools = stools;
    }

    private record Entry(UUID stoolId, GlobalPos pos) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("stool_id").forGetter(Entry::stoolId),
                GlobalPos.CODEC.fieldOf("pos").forGetter(Entry::pos)
        ).apply(i, Entry::new));
    }

    private static Codec<TavernStoolIndex> codec() {
        return Codec.list(Entry.CODEC).xmap(
                entries -> {
                    Map<UUID, GlobalPos> map = new HashMap<>();
                    for (Entry entry : entries) {
                        map.put(entry.stoolId(), entry.pos());
                    }
                    return new TavernStoolIndex(map);
                },
                data -> {
                    List<Entry> entries = new ArrayList<>(data.stools.size());
                    data.stools.forEach((stoolId, pos) -> entries.add(new Entry(stoolId, pos)));
                    return entries;
                });
    }

    public static TavernStoolIndex get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    public void put(UUID stoolId, GlobalPos pos) {
        stools.put(stoolId, pos);
        setDirty();
    }

    public void remove(UUID stoolId) {
        if (stools.remove(stoolId) != null) {
            setDirty();
        }
    }

    public Optional<GlobalPos> get(UUID stoolId) {
        return Optional.ofNullable(stools.get(stoolId));
    }

    /** Read-only snapshot of every known Tavern Stool -- used by {@code TavernDailySpawnTicker}. */
    public Map<UUID, GlobalPos> all() {
        return Map.copyOf(stools);
    }
}
