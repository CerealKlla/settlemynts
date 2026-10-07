package com.github.cerealklla.settlemynts.lumberyard;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;

/**
 * The wood species native to a Lumberyard plot's own biome, and the sapling item that plants it --
 * added 2026-10-05, explicit user request for a Lumberjack worker NPC. {@link #localSpeciesSapling}
 * is a duplicate of Blueprynts' {@code bridge.ShopSeedCatalogs#localWoodSpecies}'s exact biome checks
 * (same "duplicate small pure utility rather than add a dependency" precedent as {@code
 * ShopMidnightRestockTicker}'s day-number math) -- Settlemynts has no real dependency on Blueprynts'
 * internals beyond the existing bridge classes, and this mapping is simple/stable enough that
 * duplicating it is cheaper than exposing a new cross-mod API just for this.
 */
final class LumberjackSaplingSpecies {

    private LumberjackSaplingSpecies() {
    }

    /** The sapling item to plant at {@code pos}, based on the real vanilla biome there. */
    static Item localSpeciesSapling(ServerLevel level, BlockPos pos) {
        Holder<Biome> biome = level.getBiome(pos);
        if (biome.is(Biomes.SPARSE_JUNGLE) || biome.is(Biomes.JUNGLE) || biome.is(Biomes.BAMBOO_JUNGLE)) {
            return Items.JUNGLE_SAPLING;
        }
        if (biome.is(Biomes.TAIGA) || biome.is(Biomes.OLD_GROWTH_PINE_TAIGA) || biome.is(Biomes.OLD_GROWTH_SPRUCE_TAIGA)
                || biome.is(Biomes.SNOWY_TAIGA) || biome.is(Biomes.GROVE) || biome.is(Biomes.SNOWY_SLOPES)) {
            return Items.SPRUCE_SAPLING;
        }
        if (biome.is(Biomes.SAVANNA) || biome.is(Biomes.SAVANNA_PLATEAU) || biome.is(Biomes.WINDSWEPT_SAVANNA)) {
            return Items.ACACIA_SAPLING;
        }
        if (biome.is(Biomes.SWAMP) || biome.is(Biomes.MANGROVE_SWAMP)) {
            return Items.MANGROVE_PROPAGULE;
        }
        if (biome.is(Biomes.DARK_FOREST)) {
            return Items.DARK_OAK_SAPLING;
        }
        if (biome.is(Biomes.PALE_GARDEN)) {
            return Items.PALE_OAK_SAPLING;
        }
        if (biome.is(Biomes.CHERRY_GROVE)) {
            return Items.CHERRY_SAPLING;
        }
        if (biome.is(Biomes.BIRCH_FOREST) || biome.is(Biomes.OLD_GROWTH_BIRCH_FOREST)) {
            return Items.BIRCH_SAPLING;
        }
        return Items.OAK_SAPLING;
    }
}
