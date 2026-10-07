package com.github.cerealklla.settlemynts.farm;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * The crop a Farm plot's worker NPC plants, chosen once per plot from the biome at its anchor
 * position (added 2026-10-05, explicit user request + user-confirmed design: "mirror real vanilla
 * village generation" -- Plains/Desert/Savanna-family biomes get Wheat, Taiga/Snowy-family get
 * Potato, Swamp-family gets Beetroot, Jungle-family gets Melon, everything else defaults to Wheat
 * (the request's own literal "default to wheat or potato").
 *
 * <p>{@link PlantKind#STEM_FRUIT} (Melon) is a genuinely different vanilla mechanic from the other
 * three -- {@code MelonStemBlock} is not a {@code CropBlock} at all, so it can't use the same
 * "isMaxAge -> break -> replant" cycle. A mature stem instead spawns a separate {@code Blocks.MELON}
 * fruit block on an adjacent tile, which regrows on its own as long as the stem survives -- so the
 * worker's harvest step for this kind only ever breaks the fruit block itself, never the stem.
 */
final class FarmCropSpecies {

    enum PlantKind {
        SIMPLE_CROP,
        STEM_FRUIT
    }

    record CropChoice(PlantKind kind, Block plantBlock, Block fruitBlock, Item produceItem, int produceCount) {
    }

    static final CropChoice WHEAT = new CropChoice(PlantKind.SIMPLE_CROP, Blocks.WHEAT, null, Items.WHEAT, 1);
    static final CropChoice POTATO = new CropChoice(PlantKind.SIMPLE_CROP, Blocks.POTATOES, null, Items.POTATO, 2);
    static final CropChoice BEETROOT = new CropChoice(PlantKind.SIMPLE_CROP, Blocks.BEETROOTS, null, Items.BEETROOT, 1);
    static final CropChoice MELON = new CropChoice(PlantKind.STEM_FRUIT, Blocks.MELON_STEM, Blocks.MELON, Items.MELON_SLICE, 4);

    private FarmCropSpecies() {
    }

    static CropChoice chooseFor(ServerLevel level, BlockPos anchor) {
        Holder<Biome> biome = level.getBiome(anchor);
        if (biome.is(Biomes.TAIGA) || biome.is(Biomes.OLD_GROWTH_PINE_TAIGA) || biome.is(Biomes.OLD_GROWTH_SPRUCE_TAIGA)
                || biome.is(Biomes.SNOWY_TAIGA) || biome.is(Biomes.SNOWY_PLAINS) || biome.is(Biomes.ICE_SPIKES)
                || biome.is(Biomes.SNOWY_SLOPES) || biome.is(Biomes.GROVE) || biome.is(Biomes.SNOWY_BEACH)) {
            return POTATO;
        }
        if (biome.is(Biomes.SWAMP) || biome.is(Biomes.MANGROVE_SWAMP)) {
            return BEETROOT;
        }
        if (biome.is(Biomes.JUNGLE) || biome.is(Biomes.SPARSE_JUNGLE) || biome.is(Biomes.BAMBOO_JUNGLE)) {
            return MELON;
        }
        // Plains/Desert/Savanna-family and everything else not matched above -- the request's own
        // "default to wheat" fallback.
        return WHEAT;
    }
}
