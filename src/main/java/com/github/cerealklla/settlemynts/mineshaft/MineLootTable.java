package com.github.cerealklla.settlemynts.mineshaft;

import java.util.Optional;

import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Tier-scaled, specialization-scaled ore/stone find table for {@code MinerWorkGoal} (design added
 * 2026-10-11, explicit user request) -- the first tier-scaled *drop-chance* table anywhere in this
 * suite (the existing tier-scaled tables, e.g. {@code guardhouse.GuardhouseConstants}, are plain int
 * formulas, not a weighted item pool). A flat, per-tier weighted-entry array, same "one array
 * indexed by tier-1, flagged tunable" convention as every other per-tier table in this suite. Rarer
 * ores (Gold/Redstone/Lapis, then Diamond/Emerald) only unlock at higher tiers entirely, rather than
 * just shrinking toward zero weight.
 *
 * <p><b>Mineshaft Specialization (added 2026-10-23, explicit user request)</b> -- a Mineshaft
 * plot's Owner/Mayor/Town Planners can switch between {@link MineSpecialization#ORE} (the original
 * table: "Tier 1 will mostly be Cobblestone and Coal with a low % chance of copper... slightly less
 * cobblestone, slightly more copper, a slight chance of iron, etc.") and {@link
 * MineSpecialization#STONE} (new: mostly the "Any Stone" variety set, with the same raw ores still
 * reachable but at much lower odds). Built for genuine parity, not just a reskin: each table also
 * carries a trace amount of the OTHER table's own focus (the Ore table includes small
 * Granite/Diorite/Andesite/Tuff/Calcite/Deepslate/Amethyst Shard weights; the Stone table includes
 * small raw-ore weights) -- both tables' raw-ore weights (Copper/Iron/Gold/Redstone/Lapis/Diamond/
 * Emerald) are untouched from the original Ore table; only Cobblestone/Stone (each table's own
 * dominant filler) shrink to make room. Gravel/Flint are treated as a "stone type" for this split
 * (common in the Stone table, a trace in the Ore table), mirroring vanilla's own gravel->flint drop
 * relationship (Flint held flat/low across every Tier; Gravel shrinks with depth in both tables).
 * Every weight below was hand-tuned and explicitly reviewed/approved by the user before
 * implementation -- see the suite's own workHistory/decisions for the review thread.
 */
public final class MineLootTable {

    private record Entry(Item item, int weight) {
    }

    private static final Entry[][] ORE_TIERS = {
            // T1: mostly Cobblestone/Coal, a low chance of Copper; Granite is the one Stone-type trace.
            { new Entry(Items.COBBLESTONE, 54), new Entry(Items.COAL, 30), new Entry(Items.RAW_COPPER, 10),
                    new Entry(Items.GRAVEL, 3), new Entry(Items.FLINT, 1), new Entry(Items.GRANITE, 2) },
            // T2: slightly less Cobblestone, slightly more Copper, a slight chance of Iron; Andesite/Tuff join Granite.
            { new Entry(Items.COBBLESTONE, 37), new Entry(Items.COAL, 30), new Entry(Items.RAW_COPPER, 15), new Entry(Items.RAW_IRON, 5),
                    new Entry(Items.GRAVEL, 4), new Entry(Items.FLINT, 1), new Entry(Items.GRANITE, 3), new Entry(Items.ANDESITE, 2), new Entry(Items.TUFF, 3) },
            // T3: Iron becomes a real contributor, a first taste of Gold; Diorite/Calcite/Deepslate join as traces.
            { new Entry(Items.COBBLESTONE, 24), new Entry(Items.COAL, 25), new Entry(Items.RAW_COPPER, 15), new Entry(Items.RAW_IRON, 20), new Entry(Items.RAW_GOLD, 5),
                    new Entry(Items.GRAVEL, 3), new Entry(Items.FLINT, 1), new Entry(Items.DIORITE, 2), new Entry(Items.TUFF, 2), new Entry(Items.CALCITE, 2), new Entry(Items.DEEPSLATE, 1) },
            // T4: Iron/Gold climb further, Redstone/Lapis unlock; Deepslate becomes the dominant trace.
            { new Entry(Items.COBBLESTONE, 10), new Entry(Items.COAL, 20), new Entry(Items.RAW_COPPER, 10), new Entry(Items.RAW_IRON, 25), new Entry(Items.RAW_GOLD, 10), new Entry(Items.REDSTONE, 7), new Entry(Items.LAPIS_LAZULI, 3),
                    new Entry(Items.GRAVEL, 2), new Entry(Items.FLINT, 1), new Entry(Items.ANDESITE, 2), new Entry(Items.TUFF, 2), new Entry(Items.CALCITE, 3), new Entry(Items.DEEPSLATE, 5) },
            // T5: Diamond/Emerald unlock, common ores shrink to a minority of the pool; Amethyst Shard is the one Tier-5-only trace.
            { new Entry(Items.COAL, 15), new Entry(Items.RAW_COPPER, 5), new Entry(Items.RAW_IRON, 20), new Entry(Items.RAW_GOLD, 15), new Entry(Items.REDSTONE, 10), new Entry(Items.LAPIS_LAZULI, 10), new Entry(Items.DIAMOND, 7), new Entry(Items.EMERALD, 3),
                    new Entry(Items.GRAVEL, 1), new Entry(Items.FLINT, 1), new Entry(Items.CALCITE, 2), new Entry(Items.DEEPSLATE, 6), new Entry(Items.AMETHYST_SHARD, 5) },
    };

    private static final Entry[][] STONE_TIERS = {
            // T1: common surface stone + Gravel/Flint; trace Copper only.
            { new Entry(Items.STONE, 32), new Entry(Items.COBBLESTONE, 20), new Entry(Items.GRAVEL, 15), new Entry(Items.FLINT, 3),
                    new Entry(Items.GRANITE, 10), new Entry(Items.DIORITE, 10), new Entry(Items.ANDESITE, 8), new Entry(Items.RAW_COPPER, 2) },
            // T2: Tuff unlocks; Iron becomes reachable but rare.
            { new Entry(Items.STONE, 24), new Entry(Items.COBBLESTONE, 15), new Entry(Items.GRAVEL, 12), new Entry(Items.FLINT, 3),
                    new Entry(Items.GRANITE, 10), new Entry(Items.DIORITE, 10), new Entry(Items.ANDESITE, 8), new Entry(Items.TUFF, 10),
                    new Entry(Items.RAW_COPPER, 5), new Entry(Items.RAW_IRON, 3) },
            // T3: Calcite + Deepslate debut; first trace Gold.
            { new Entry(Items.STONE, 17), new Entry(Items.COBBLESTONE, 10), new Entry(Items.GRAVEL, 8), new Entry(Items.FLINT, 2),
                    new Entry(Items.GRANITE, 8), new Entry(Items.DIORITE, 8), new Entry(Items.ANDESITE, 7), new Entry(Items.TUFF, 10), new Entry(Items.CALCITE, 8), new Entry(Items.DEEPSLATE, 15),
                    new Entry(Items.RAW_COPPER, 4), new Entry(Items.RAW_IRON, 2), new Entry(Items.RAW_GOLD, 1) },
            // T4: Deepslate becomes dominant; Redstone/Lapis unlock at trace levels.
            { new Entry(Items.STONE, 10), new Entry(Items.COBBLESTONE, 7), new Entry(Items.GRAVEL, 5), new Entry(Items.FLINT, 2),
                    new Entry(Items.GRANITE, 6), new Entry(Items.DIORITE, 6), new Entry(Items.ANDESITE, 6), new Entry(Items.TUFF, 10), new Entry(Items.CALCITE, 8), new Entry(Items.DEEPSLATE, 28),
                    new Entry(Items.RAW_COPPER, 3), new Entry(Items.RAW_IRON, 4), new Entry(Items.RAW_GOLD, 2), new Entry(Items.REDSTONE, 2), new Entry(Items.LAPIS_LAZULI, 1) },
            // T5: Deepslate + Amethyst Shard dominate; Diamond/Emerald technically reachable but rare.
            { new Entry(Items.STONE, 5), new Entry(Items.COBBLESTONE, 3), new Entry(Items.GRAVEL, 3), new Entry(Items.FLINT, 1),
                    new Entry(Items.GRANITE, 4), new Entry(Items.DIORITE, 4), new Entry(Items.ANDESITE, 4), new Entry(Items.TUFF, 8), new Entry(Items.CALCITE, 7), new Entry(Items.DEEPSLATE, 38), new Entry(Items.AMETHYST_SHARD, 10),
                    new Entry(Items.RAW_COPPER, 2), new Entry(Items.RAW_IRON, 4), new Entry(Items.RAW_GOLD, 2), new Entry(Items.REDSTONE, 2), new Entry(Items.LAPIS_LAZULI, 1), new Entry(Items.DIAMOND, 1), new Entry(Items.EMERALD, 1) },
    };

    private static final double FIND_CHANCE = 0.5; // Flat 50% per return trip, per the user's own spec.
    private static final int MIN_FIND_COUNT = 1;
    private static final int MAX_FIND_COUNT = 4;

    private MineLootTable() {
    }

    /**
     * {@code tier} is the 1-based plot Tier (1-5); out-of-range values clamp. {@code specialization}
     * picks which table to roll against -- see this class's own doc. Empty if the 50% gate didn't
     * hit.
     */
    public static Optional<ItemStack> rollLoot(int tier, MineSpecialization specialization, RandomSource random) {
        if (random.nextDouble() >= FIND_CHANCE) {
            return Optional.empty();
        }
        Entry[][] tiers = specialization == MineSpecialization.STONE ? STONE_TIERS : ORE_TIERS;
        Entry[] pool = tiers[Math.max(1, Math.min(tiers.length, tier)) - 1];
        int totalWeight = 0;
        for (Entry entry : pool) {
            totalWeight += entry.weight();
        }
        int roll = random.nextInt(totalWeight);
        for (Entry entry : pool) {
            if (roll < entry.weight()) {
                int count = MIN_FIND_COUNT + random.nextInt(MAX_FIND_COUNT - MIN_FIND_COUNT + 1);
                return Optional.of(new ItemStack(entry.item(), count));
            }
            roll -= entry.weight();
        }
        return Optional.empty(); // Unreachable (weights always sum to totalWeight), kept as a safe fallback.
    }
}
