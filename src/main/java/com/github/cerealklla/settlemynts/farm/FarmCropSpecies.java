package com.github.cerealklla.settlemynts.farm;

import java.util.List;
import java.util.Optional;

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

    /**
     * {@code seedItem} added 2026-10-28 (Plant Selection) -- the real item a player holds to
     * configure/plant this crop; {@code null} for Potato/Carrot, vanilla's own "self-seeding" crops
     * where the harvested produce item doubles as the planting material (no separate Seeds item
     * exists for either). {@link #forSeedItem} is the reverse lookup this feature actually needs.
     */
    record CropChoice(PlantKind kind, Block plantBlock, Block fruitBlock, Item produceItem, int produceCount, Item seedItem) {
    }

    static final CropChoice WHEAT = new CropChoice(PlantKind.SIMPLE_CROP, Blocks.WHEAT, null, Items.WHEAT, 1, Items.WHEAT_SEEDS);
    static final CropChoice POTATO = new CropChoice(PlantKind.SIMPLE_CROP, Blocks.POTATOES, null, Items.POTATO, 2, Items.POTATO);
    static final CropChoice BEETROOT = new CropChoice(PlantKind.SIMPLE_CROP, Blocks.BEETROOTS, null, Items.BEETROOT, 1, Items.BEETROOT_SEEDS);
    static final CropChoice MELON = new CropChoice(PlantKind.STEM_FRUIT, Blocks.MELON_STEM, Blocks.MELON, Items.MELON_SLICE, 4, Items.MELON_SEEDS);
    /** Added 2026-10-28 (Plant Selection, "All Seeds should be listed") -- same STEM_FRUIT shape as Melon, vanilla's identical Pumpkin Stem/Pumpkin pair. */
    static final CropChoice PUMPKIN = new CropChoice(PlantKind.STEM_FRUIT, Blocks.PUMPKIN_STEM, Blocks.PUMPKIN, Items.PUMPKIN, 1, Items.PUMPKIN_SEEDS);
    /** Added 2026-10-28, Carrot's own self-seeding twin to Potato (same reasoning as {@link #POTATO}'s own doc). */
    static final CropChoice CARROT = new CropChoice(PlantKind.SIMPLE_CROP, Blocks.CARROTS, null, Items.CARROT, 2, Items.CARROT);

    /**
     * Every crop this mod knows how to grow -- the Plant Selection config screen only ever accepts a
     * held item that resolves to one of these via {@link #forSeedItem}, and the default (never-
     * configured) worker behavior still falls back to {@link #chooseFor}'s single biome pick,
     * unaffected by this list's existence. Torchflower/Pitcher Pod are a deliberate, documented scope
     * cut -- real vanilla seeds, but with no existing harvest-cycle precedent anywhere in this class
     * (Torchflower is a non-replanting decorative flower, Pitcher Pod a two-stage sideways-growing
     * plant neither mechanic here was built to handle) -- not added without a closer look at their
     * real growth mechanics first.
     */
    static final List<CropChoice> ALL = List.of(WHEAT, POTATO, BEETROOT, MELON, PUMPKIN, CARROT);

    /** Reverse of each {@link CropChoice#seedItem} -- what a held item configures/plants, if it's a real known crop's seed at all. */
    static Optional<CropChoice> forSeedItem(final Item seedItem) {
        return ALL.stream().filter(choice -> choice.seedItem() == seedItem).findFirst();
    }

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
