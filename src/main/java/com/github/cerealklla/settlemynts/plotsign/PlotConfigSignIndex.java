package com.github.cerealklla.settlemynts.plotsign;

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
 * Save-wide index from a plot's own {@code plotId} to its Plot Config Sign's current {@link
 * GlobalPos} -- mirrors Blueprynts' own {@code construction.ConstructionBoxIndex} exactly, including
 * its "{@code MinecraftServer}-scoped, never {@code ServerLevel#getDataStorage()}" reasoning (a
 * settlement/plot isn't dimension-scoped either). One sign per plot, so {@code plotId} (already a
 * stable identity -- {@code PlotRecord#plotId}) is the key directly; no separate sign id is minted.
 */
public final class PlotConfigSignIndex extends SavedData {

    public static final SavedDataType<PlotConfigSignIndex> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "plot_config_sign_index"),
            PlotConfigSignIndex::new,
            codec());

    private final Map<UUID, GlobalPos> signs;

    PlotConfigSignIndex() {
        this(new HashMap<>());
    }

    private PlotConfigSignIndex(Map<UUID, GlobalPos> signs) {
        this.signs = signs;
    }

    private record Entry(UUID plotId, GlobalPos pos) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("plot_id").forGetter(Entry::plotId),
                GlobalPos.CODEC.fieldOf("pos").forGetter(Entry::pos)
        ).apply(i, Entry::new));
    }

    private static Codec<PlotConfigSignIndex> codec() {
        return Codec.list(Entry.CODEC).xmap(
                entries -> {
                    Map<UUID, GlobalPos> map = new HashMap<>();
                    for (Entry entry : entries) {
                        map.put(entry.plotId(), entry.pos());
                    }
                    return new PlotConfigSignIndex(map);
                },
                data -> {
                    List<Entry> entries = new ArrayList<>(data.signs.size());
                    data.signs.forEach((plotId, pos) -> entries.add(new Entry(plotId, pos)));
                    return entries;
                });
    }

    public static PlotConfigSignIndex get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    public void put(UUID plotId, GlobalPos pos) {
        signs.put(plotId, pos);
        setDirty();
    }

    public void remove(UUID plotId) {
        signs.remove(plotId);
        setDirty();
    }

    public Optional<GlobalPos> get(UUID plotId) {
        return Optional.ofNullable(signs.get(plotId));
    }

    public boolean has(UUID plotId) {
        return signs.containsKey(plotId);
    }

    /** Every plot's sign position, keyed by plotId -- see {@code resident.ResidentSpawnTicker}, which mirrors {@code guardhouse.GuardSpawnTicker}'s own {@code GuardhouseIndex.all()} usage. */
    public Map<UUID, GlobalPos> all() {
        return Map.copyOf(signs);
    }
}
