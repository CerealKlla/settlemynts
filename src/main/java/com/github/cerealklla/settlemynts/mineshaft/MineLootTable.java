package com.github.cerealklla.settlemynts.mineshaft;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
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
 * cobblestone, slightly more copper, a slight chance of iron, etc."), {@link
 * MineSpecialization#STONE} (mostly the "Any Stone" variety set), and {@link
 * MineSpecialization#EXCAVATION} (added same day -- shovel-digging focus: Dirt/Gravel/Sand/Clay/
 * Flint, with Gravel/Flint having moved here from Stone since they're a shovel's own domain, not a
 * pickaxe's). Picture the three as a spectrum, Excavation --- Ore --- Stone, with Ore in the middle:
 * <ul>
 * <li>Each table's own dominant filler (Cobblestone/Coal/raw-ore for Ore, Stone/Granite/Diorite/
 * etc. for Stone, Dirt/Gravel/Sand/Clay/Flint for Excavation) is untouched by the other two tables'
 * own focus -- Ore's raw-ore progression in particular is never touched by this split at all.
 * <li>Ore carries an *equal* trace of both Stone-type and Excavation-type items at every Tier
 * (explicit user spec: "when specializing in Ore you'd get equal lesser odds of Stone or
 * Excavation").
 * <li>Stone carries the exact same raw-ore trace weights Ore's own raw-ore progression would give
 * at that Tier (Copper/Iron/Gold/Redstone/Lapis/Diamond/Emerald -- copied verbatim), plus only a
 * flat, minimal Dirt trace standing in for Excavation's whole focus ("almost no odds of the
 * other").
 * <li>Excavation mirrors Stone exactly in reverse: the identical raw-ore trace weights (same
 * numbers as Stone's, since Ore sits an equal "one step" from both), plus only a flat, minimal
 * single-stone-type trace standing in for Stone's whole focus.
 * </ul>
 * Every weight below was hand-tuned and explicitly reviewed/approved by the user before
 * implementation -- see the suite's own workHistory/decisions for the review thread.
 */
public final class MineLootTable {

    /** Widened from {@code private}, 2026-10-28, so the Configure tab's new odds display can read a tier's real weighted entries -- see {@link #entriesFor}. */
    public record Entry(Item item, int weight) {
    }

    private static final Entry[][] ORE_TIERS = {
            // T1: mostly Cobblestone/Coal, a low chance of Copper; equal Stone/Excavation trace (3 each).
            { new Entry(Items.COBBLESTONE, 54), new Entry(Items.COAL, 30), new Entry(Items.RAW_COPPER, 10),
                    new Entry(Items.GRANITE, 3),
                    new Entry(Items.GRAVEL, 2), new Entry(Items.FLINT, 1) },
            // T2: slightly less Cobblestone, slightly more Copper, a slight chance of Iron; equal trace (7 each).
            { new Entry(Items.COBBLESTONE, 37), new Entry(Items.COAL, 30), new Entry(Items.RAW_COPPER, 15), new Entry(Items.RAW_IRON, 5),
                    new Entry(Items.GRANITE, 3), new Entry(Items.ANDESITE, 2), new Entry(Items.TUFF, 2),
                    new Entry(Items.GRAVEL, 5), new Entry(Items.FLINT, 2) },
            // T3: Iron becomes a real contributor, a first taste of Gold; equal trace (6 each).
            { new Entry(Items.COBBLESTONE, 24), new Entry(Items.COAL, 25), new Entry(Items.RAW_COPPER, 15), new Entry(Items.RAW_IRON, 20), new Entry(Items.RAW_GOLD, 5),
                    new Entry(Items.DIORITE, 2), new Entry(Items.TUFF, 2), new Entry(Items.CALCITE, 1), new Entry(Items.DEEPSLATE, 1),
                    new Entry(Items.GRAVEL, 4), new Entry(Items.FLINT, 2) },
            // T4: Iron/Gold climb further, Redstone/Lapis unlock; equal trace (8 each).
            { new Entry(Items.COBBLESTONE, 10), new Entry(Items.COAL, 20), new Entry(Items.RAW_COPPER, 10), new Entry(Items.RAW_IRON, 25), new Entry(Items.RAW_GOLD, 10), new Entry(Items.REDSTONE, 7), new Entry(Items.LAPIS_LAZULI, 3),
                    new Entry(Items.ANDESITE, 2), new Entry(Items.TUFF, 2), new Entry(Items.CALCITE, 2), new Entry(Items.DEEPSLATE, 2),
                    new Entry(Items.GRAVEL, 6), new Entry(Items.FLINT, 2) },
            // T5: Diamond/Emerald unlock, common ores shrink to a minority of the pool; equal trace (8 each).
            { new Entry(Items.COAL, 15), new Entry(Items.RAW_COPPER, 5), new Entry(Items.RAW_IRON, 20), new Entry(Items.RAW_GOLD, 15), new Entry(Items.REDSTONE, 10), new Entry(Items.LAPIS_LAZULI, 10), new Entry(Items.DIAMOND, 7), new Entry(Items.EMERALD, 3),
                    new Entry(Items.CALCITE, 2), new Entry(Items.DEEPSLATE, 4), new Entry(Items.AMETHYST_SHARD, 2),
                    new Entry(Items.GRAVEL, 5), new Entry(Items.FLINT, 3) },
    };

    private static final Entry[][] STONE_TIERS = {
            // T1: common surface stone; trace Copper (Ore) + a minimal Dirt trace (Excavation).
            { new Entry(Items.STONE, 32), new Entry(Items.COBBLESTONE, 20),
                    new Entry(Items.GRANITE, 10), new Entry(Items.DIORITE, 10), new Entry(Items.ANDESITE, 8),
                    new Entry(Items.RAW_COPPER, 2), new Entry(Items.DIRT, 2) },
            // T2: Tuff unlocks; Iron becomes reachable but rare.
            { new Entry(Items.STONE, 24), new Entry(Items.COBBLESTONE, 15),
                    new Entry(Items.GRANITE, 10), new Entry(Items.DIORITE, 10), new Entry(Items.ANDESITE, 8), new Entry(Items.TUFF, 10),
                    new Entry(Items.RAW_COPPER, 5), new Entry(Items.RAW_IRON, 3), new Entry(Items.DIRT, 2) },
            // T3: Calcite + Deepslate debut; first trace Gold.
            { new Entry(Items.STONE, 17), new Entry(Items.COBBLESTONE, 10),
                    new Entry(Items.GRANITE, 8), new Entry(Items.DIORITE, 8), new Entry(Items.ANDESITE, 7), new Entry(Items.TUFF, 10), new Entry(Items.CALCITE, 8), new Entry(Items.DEEPSLATE, 15),
                    new Entry(Items.RAW_COPPER, 4), new Entry(Items.RAW_IRON, 2), new Entry(Items.RAW_GOLD, 1), new Entry(Items.DIRT, 2) },
            // T4: Deepslate becomes dominant; Redstone/Lapis unlock at trace levels.
            { new Entry(Items.STONE, 10), new Entry(Items.COBBLESTONE, 7),
                    new Entry(Items.GRANITE, 6), new Entry(Items.DIORITE, 6), new Entry(Items.ANDESITE, 6), new Entry(Items.TUFF, 10), new Entry(Items.CALCITE, 8), new Entry(Items.DEEPSLATE, 28),
                    new Entry(Items.RAW_COPPER, 3), new Entry(Items.RAW_IRON, 4), new Entry(Items.RAW_GOLD, 2), new Entry(Items.REDSTONE, 2), new Entry(Items.LAPIS_LAZULI, 1), new Entry(Items.DIRT, 2) },
            // T5: Deepslate + Amethyst Shard dominate; Diamond/Emerald technically reachable but rare.
            { new Entry(Items.STONE, 5), new Entry(Items.COBBLESTONE, 3),
                    new Entry(Items.GRANITE, 4), new Entry(Items.DIORITE, 4), new Entry(Items.ANDESITE, 4), new Entry(Items.TUFF, 8), new Entry(Items.CALCITE, 7), new Entry(Items.DEEPSLATE, 38), new Entry(Items.AMETHYST_SHARD, 10),
                    new Entry(Items.RAW_COPPER, 2), new Entry(Items.RAW_IRON, 4), new Entry(Items.RAW_GOLD, 2), new Entry(Items.REDSTONE, 2), new Entry(Items.LAPIS_LAZULI, 1), new Entry(Items.DIAMOND, 1), new Entry(Items.EMERALD, 1), new Entry(Items.DIRT, 2) },
    };

    private static final Entry[][] EXCAVATION_TIERS = {
            // T1: mostly Dirt/Sand near the surface; trace Copper (Ore) + a minimal Granite trace (Stone).
            { new Entry(Items.DIRT, 35), new Entry(Items.GRAVEL, 20), new Entry(Items.SAND, 15), new Entry(Items.CLAY_BALL, 5), new Entry(Items.FLINT, 5),
                    new Entry(Items.RAW_COPPER, 2), new Entry(Items.GRANITE, 2) },
            // T2: Dirt/Sand shrink, Gravel/Clay/Flint climb.
            { new Entry(Items.DIRT, 28), new Entry(Items.GRAVEL, 22), new Entry(Items.SAND, 12), new Entry(Items.CLAY_BALL, 7), new Entry(Items.FLINT, 6),
                    new Entry(Items.RAW_COPPER, 5), new Entry(Items.RAW_IRON, 3), new Entry(Items.ANDESITE, 2) },
            // T3: Gravel becomes the dominant filler; first trace Gold.
            { new Entry(Items.DIRT, 20), new Entry(Items.GRAVEL, 25), new Entry(Items.SAND, 10), new Entry(Items.CLAY_BALL, 10), new Entry(Items.FLINT, 8),
                    new Entry(Items.RAW_COPPER, 4), new Entry(Items.RAW_IRON, 2), new Entry(Items.RAW_GOLD, 1), new Entry(Items.DIORITE, 2) },
            // T4: Dirt/Sand down to a minority, Clay/Flint real contributors; Redstone/Lapis unlock at trace levels.
            { new Entry(Items.DIRT, 12), new Entry(Items.GRAVEL, 28), new Entry(Items.SAND, 6), new Entry(Items.CLAY_BALL, 12), new Entry(Items.FLINT, 10),
                    new Entry(Items.RAW_COPPER, 3), new Entry(Items.RAW_IRON, 4), new Entry(Items.RAW_GOLD, 2), new Entry(Items.REDSTONE, 2), new Entry(Items.LAPIS_LAZULI, 1), new Entry(Items.CALCITE, 2) },
            // T5: Gravel/Clay/Flint dominate, Dirt/Sand nearly gone; Diamond/Emerald technically reachable but rare.
            { new Entry(Items.DIRT, 5), new Entry(Items.GRAVEL, 30), new Entry(Items.SAND, 3), new Entry(Items.CLAY_BALL, 12), new Entry(Items.FLINT, 12),
                    new Entry(Items.RAW_COPPER, 2), new Entry(Items.RAW_IRON, 4), new Entry(Items.RAW_GOLD, 2), new Entry(Items.REDSTONE, 2), new Entry(Items.LAPIS_LAZULI, 1), new Entry(Items.DIAMOND, 1), new Entry(Items.EMERALD, 1), new Entry(Items.DEEPSLATE, 2) },
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
        Entry[][] tiers = switch (specialization) {
            case STONE -> STONE_TIERS;
            case EXCAVATION -> EXCAVATION_TIERS;
            case ORE -> ORE_TIERS;
        };
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

    /**
     * The real weighted entries for {@code tier}+{@code specialization}, sorted highest-weight-first
     * -- for the Configure tab's new "what can this Mineshaft find, and roughly how often" display
     * (explicit user request, 2026-10-28). Same tier-clamping as {@link #rollLoot}. Deliberately
     * returns raw weights, not a 0-5 dot rating -- the caller derives that relative to the group's
     * own highest weight (the user's own spec: "0% (0 dots) to the highest % available in this table
     * (5 dots)"), since "highest" only means something once every entry in the table is in hand.
     */
    public static List<Entry> entriesFor(int tier, MineSpecialization specialization) {
        Entry[][] tiers = switch (specialization) {
            case STONE -> STONE_TIERS;
            case EXCAVATION -> EXCAVATION_TIERS;
            case ORE -> ORE_TIERS;
        };
        Entry[] pool = tiers[Math.max(1, Math.min(tiers.length, tier)) - 1];
        return Arrays.stream(pool).sorted(Comparator.comparingInt(Entry::weight).reversed()).toList();
    }
}
