package com.github.cerealklla.settlemynts.founding;

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
 * Save-wide, chunk-independent index from a settlement's {@code GhostTownHallCoreEntity#getUUID()}
 * to its last-known real-world position -- mirrors {@code watchtower.WatchTowerIndex}/{@code
 * guardhouse.GuardhouseIndex} exactly. Added 2026-10-22 for the midnight settlement-processing fix
 * (see decisions.md "Midnight settlement processing must force-load chunks") -- the one real gap
 * every existing lookup of the core had: {@code ServerLevel#getEntity(UUID)} can only ever find an
 * entity that's already loaded, and (unlike a {@code BlockEntity} lookup by a known {@code BlockPos})
 * there is no way to force-load "whichever chunk this UUID happens to be in" without already knowing
 * where to look. This index is that missing "where to look," updated at creation ({@link
 * GhostTownHallCoreEntity#create}) and at relocation ({@code TownHallCoreRelocatorItem#useOn}).
 */
public final class SettlementCoreIndex extends SavedData {

    public static final SavedDataType<SettlementCoreIndex> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "settlement_core_index"),
            SettlementCoreIndex::new,
            codec());

    private final Map<UUID, GlobalPos> cores;

    SettlementCoreIndex() {
        this(new HashMap<>());
    }

    private SettlementCoreIndex(Map<UUID, GlobalPos> cores) {
        this.cores = cores;
    }

    private record Entry(UUID coreId, GlobalPos pos) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("core_id").forGetter(Entry::coreId),
                GlobalPos.CODEC.fieldOf("pos").forGetter(Entry::pos)
        ).apply(i, Entry::new));
    }

    private static Codec<SettlementCoreIndex> codec() {
        return Codec.list(Entry.CODEC).xmap(
                entries -> {
                    Map<UUID, GlobalPos> map = new HashMap<>();
                    for (Entry entry : entries) {
                        map.put(entry.coreId(), entry.pos());
                    }
                    return new SettlementCoreIndex(map);
                },
                data -> {
                    List<Entry> entries = new ArrayList<>(data.cores.size());
                    data.cores.forEach((coreId, pos) -> entries.add(new Entry(coreId, pos)));
                    return entries;
                });
    }

    public static SettlementCoreIndex get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    public void put(UUID coreId, GlobalPos pos) {
        cores.put(coreId, pos);
        setDirty();
    }

    public void remove(UUID coreId) {
        cores.remove(coreId);
        setDirty();
    }

    public Optional<GlobalPos> get(UUID coreId) {
        return Optional.ofNullable(cores.get(coreId));
    }

    /** Every known settlement core's position -- see {@code zone.MidnightSettlementWaker}, the primary consumer. */
    public Map<UUID, GlobalPos> all() {
        return Map.copyOf(cores);
    }
}
