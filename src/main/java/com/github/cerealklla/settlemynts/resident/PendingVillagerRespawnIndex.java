package com.github.cerealklla.settlemynts.resident;

import java.util.ArrayList;
import java.util.List;

import com.mojang.serialization.Codec;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Save-wide list of {@link PendingVillagerRespawn}s -- see that record's own doc, and {@link
 * VillagerDeathListener}/{@link VillagerRespawnTicker}, the only writer/reader.
 * {@code MinecraftServer}-scoped rather than per-{@code ServerLevel}, same reasoning as {@code
 * plotsign.PlotConfigSignIndex} (a death can be recorded in one dimension and the scan is cheap
 * enough to just check every pending entry's own stored dimension each pass).
 */
public final class PendingVillagerRespawnIndex extends SavedData {

    public static final SavedDataType<PendingVillagerRespawnIndex> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "pending_villager_respawns"),
            PendingVillagerRespawnIndex::new,
            codec());

    private final List<PendingVillagerRespawn> pending;

    PendingVillagerRespawnIndex() {
        this(new ArrayList<>());
    }

    private PendingVillagerRespawnIndex(List<PendingVillagerRespawn> pending) {
        this.pending = pending;
    }

    private static Codec<PendingVillagerRespawnIndex> codec() {
        return PendingVillagerRespawn.CODEC.listOf().xmap(
                list -> new PendingVillagerRespawnIndex(new ArrayList<>(list)),
                data -> List.copyOf(data.pending));
    }

    public static PendingVillagerRespawnIndex get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    public void add(PendingVillagerRespawn entry) {
        pending.add(entry);
        setDirty();
    }

    public List<PendingVillagerRespawn> all() {
        return List.copyOf(pending);
    }

    public void remove(PendingVillagerRespawn entry) {
        if (pending.remove(entry)) {
            setDirty();
        }
    }
}
