package com.github.cerealklla.settlemynts.zone;

import java.util.Map;
import java.util.Set;

import net.minecraft.core.Holder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;

/**
 * Biome -> wood-species planks id, for backlog item 7's "LocalWood" placeholder substitution: "if
 * the blueprint is built in a jungle, the LocalWood would be jungle wood, not oak." A pragmatic
 * heuristic mapping (vanilla has no single generic "this biome's native tree species" API) grouped
 * by each biome family's own dominant tree type in vanilla's own world generation -- not exhaustive
 * tag-matching, just the real biome keys vanilla ships today. Anything not explicitly listed (every
 * ocean/river/beach/cave/desert/plains/swamp/etc., and anything from a datapack this doesn't know
 * about) falls back to oak, the same reasonable default a real player-built town would use absent a
 * stronger signal.
 */
public final class LocalWoodResolver {

    private LocalWoodResolver() {
    }

    public static final Identifier OAK_PLANKS = Identifier.withDefaultNamespace("oak_planks");

    private static final Map<ResourceKey<Biome>, Identifier> BY_BIOME = Map.ofEntries(
            entry(Biomes.JUNGLE, "jungle_planks"),
            entry(Biomes.SPARSE_JUNGLE, "jungle_planks"),
            entry(Biomes.BAMBOO_JUNGLE, "jungle_planks"),
            entry(Biomes.TAIGA, "spruce_planks"),
            entry(Biomes.SNOWY_TAIGA, "spruce_planks"),
            entry(Biomes.OLD_GROWTH_PINE_TAIGA, "spruce_planks"),
            entry(Biomes.OLD_GROWTH_SPRUCE_TAIGA, "spruce_planks"),
            entry(Biomes.GROVE, "spruce_planks"),
            entry(Biomes.SNOWY_SLOPES, "spruce_planks"),
            entry(Biomes.FROZEN_PEAKS, "spruce_planks"),
            entry(Biomes.JAGGED_PEAKS, "spruce_planks"),
            entry(Biomes.STONY_PEAKS, "spruce_planks"),
            entry(Biomes.BIRCH_FOREST, "birch_planks"),
            entry(Biomes.OLD_GROWTH_BIRCH_FOREST, "birch_planks"),
            entry(Biomes.SAVANNA, "acacia_planks"),
            entry(Biomes.SAVANNA_PLATEAU, "acacia_planks"),
            entry(Biomes.WINDSWEPT_SAVANNA, "acacia_planks"),
            entry(Biomes.BADLANDS, "acacia_planks"),
            entry(Biomes.ERODED_BADLANDS, "acacia_planks"),
            entry(Biomes.WOODED_BADLANDS, "acacia_planks"),
            entry(Biomes.DARK_FOREST, "dark_oak_planks"),
            entry(Biomes.PALE_GARDEN, "pale_oak_planks"),
            entry(Biomes.MANGROVE_SWAMP, "mangrove_planks"),
            entry(Biomes.CHERRY_GROVE, "cherry_planks"),
            entry(Biomes.WARPED_FOREST, "warped_planks"),
            entry(Biomes.CRIMSON_FOREST, "crimson_planks"));

    private static Map.Entry<ResourceKey<Biome>, Identifier> entry(ResourceKey<Biome> key, String planksPath) {
        return Map.entry(key, Identifier.withDefaultNamespace(planksPath));
    }

    /** The local wood species' planks block id for {@code pos}'s own biome -- {@link #OAK_PLANKS} if the biome isn't one of the mapped families above, or doesn't resolve at all. */
    public static Identifier resolve(ServerLevel level, BlockPos pos) {
        Holder<Biome> biome = level.getBiome(pos);
        return biome.unwrapKey().map(key -> BY_BIOME.getOrDefault(key, OAK_PLANKS)).orElse(OAK_PLANKS);
    }
}
