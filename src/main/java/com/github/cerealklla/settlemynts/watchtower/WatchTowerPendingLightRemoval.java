package com.github.cerealklla.settlemynts.watchtower;

import java.util.ArrayList;
import java.util.List;

import com.mojang.serialization.Codec;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * A small, persisted "still need to clear these real {@code Blocks.LIGHT} positions" set --
 * {@link WatchTowerLightController#removeOne} queues here only when a position's own chunk isn't
 * currently loaded (never force-loaded just to clear an invisible block, same discipline that
 * motivated this whole mechanism -- see that class's own doc). {@link WatchTowerChunkLoadListener}
 * drains matching entries the moment their chunk actually loads on its own. Expected to stay small
 * (only entries from a very recent radius-shrink/removal while the player was far away) -- a plain
 * list with a linear scan on each chunk load is intentionally simple, same shape {@code
 * guardhouse.GuardhouseIndex} already uses for its own small save-wide index.
 */
public final class WatchTowerPendingLightRemoval extends SavedData {

    public static final SavedDataType<WatchTowerPendingLightRemoval> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "watch_tower_pending_light_removal"),
            WatchTowerPendingLightRemoval::new,
            Codec.list(GlobalPos.CODEC).xmap(WatchTowerPendingLightRemoval::new, data -> List.copyOf(data.pending)));

    private final List<GlobalPos> pending;

    WatchTowerPendingLightRemoval() {
        this(new ArrayList<>());
    }

    private WatchTowerPendingLightRemoval(List<GlobalPos> pending) {
        this.pending = new ArrayList<>(pending);
    }

    public static WatchTowerPendingLightRemoval get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    public void queue(ResourceKey<Level> dimension, BlockPos pos) {
        pending.add(GlobalPos.of(dimension, pos));
        setDirty();
    }

    /** Pulls out (and removes) every pending position belonging to the given loaded chunk -- called only by {@link WatchTowerChunkLoadListener}. */
    public List<GlobalPos> drain(ResourceKey<Level> dimension, ChunkPos chunkPos) {
        List<GlobalPos> matches = new ArrayList<>();
        pending.removeIf(entry -> {
            if (entry.dimension().equals(dimension) && ChunkPos.containing(entry.pos()).equals(chunkPos)) {
                matches.add(entry);
                return true;
            }
            return false;
        });
        if (!matches.isEmpty()) {
            setDirty();
        }
        return matches;
    }
}
