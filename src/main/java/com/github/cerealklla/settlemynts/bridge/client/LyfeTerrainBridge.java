package com.github.cerealklla.settlemynts.bridge.client;

import com.github.cerealklla.lyfe.map.cache.MapPalette;
import com.github.cerealklla.lyfe.map.cache.TerrainCache;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

/**
 * Client-side-only bridge onto Lyfe's persisted terrain cache -- added 2026-10-13 so {@code
 * plotsign.client.EstablishHighwayMapScreen} can show the Mayor's own real, previously-explored
 * terrain (not a flat placeholder square) behind the destination picker, exactly like Lyfe's own
 * full-screen Map does. Same optional-soft-dependency isolation convention as every other Lyfe
 * bridge in this mod -- every call site must check {@link #isLoaded()} itself first. Never samples
 * live world data; a column the player has never actually explored simply stays transparent.
 */
public final class LyfeTerrainBridge {

    private LyfeTerrainBridge() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded("lyfe");
    }

    /** ARGB color for the terrain cached at this column, or fully transparent if never sampled. */
    public static int sampleArgb(ResourceKey<Level> dimension, int blockX, int blockZ) {
        return MapPalette.argb(TerrainCache.get(dimension, blockX, blockZ));
    }
}
