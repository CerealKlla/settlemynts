package com.github.cerealklla.settlemynts.mineshaft;

import java.util.Optional;

import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Tier-scaled ore find table for {@code MinerWorkGoal} (design added 2026-10-11, explicit user
 * request) -- the first tier-scaled *drop-chance* table anywhere in this suite (the existing
 * tier-scaled tables, e.g. {@code guardhouse.GuardhouseConstants}, are plain int formulas, not a
 * weighted item pool). "Tier 1 will mostly be Cobblestone and Coal with a low % chance of copper...
 * slightly less cobblestone, slightly more copper, a slight chance of iron, etc." -- a flat,
 * per-tier weighted-entry array, same "one array indexed by tier-1, flagged tunable" convention as
 * every other per-tier table in this suite. Rarer ores (Gold/Redstone/Lapis, then Diamond/Emerald)
 * only unlock at higher tiers entirely, rather than just shrinking toward zero weight.
 */
public final class MineLootTable {

    private record Entry(Item item, int weight) {
    }

    private static final Entry[][] TIERS = {
            // T1: mostly Cobblestone/Coal, a low chance of Copper.
            { new Entry(Items.COBBLESTONE, 60), new Entry(Items.COAL, 30), new Entry(Items.RAW_COPPER, 10) },
            // T2: slightly less Cobblestone, slightly more Copper, a slight chance of Iron.
            { new Entry(Items.COBBLESTONE, 50), new Entry(Items.COAL, 30), new Entry(Items.RAW_COPPER, 15), new Entry(Items.RAW_IRON, 5) },
            // T3: Iron becomes a real contributor, a first taste of Gold.
            { new Entry(Items.COBBLESTONE, 35), new Entry(Items.COAL, 25), new Entry(Items.RAW_COPPER, 15), new Entry(Items.RAW_IRON, 20), new Entry(Items.RAW_GOLD, 5) },
            // T4: Iron/Gold climb further, Redstone/Lapis unlock.
            { new Entry(Items.COBBLESTONE, 25), new Entry(Items.COAL, 20), new Entry(Items.RAW_COPPER, 10), new Entry(Items.RAW_IRON, 25), new Entry(Items.RAW_GOLD, 10), new Entry(Items.REDSTONE, 7), new Entry(Items.LAPIS_LAZULI, 3) },
            // T5: Diamond/Emerald unlock, common ores shrink to a minority of the pool.
            { new Entry(Items.COBBLESTONE, 15), new Entry(Items.COAL, 15), new Entry(Items.RAW_COPPER, 5), new Entry(Items.RAW_IRON, 20), new Entry(Items.RAW_GOLD, 15), new Entry(Items.REDSTONE, 10), new Entry(Items.LAPIS_LAZULI, 10), new Entry(Items.DIAMOND, 7), new Entry(Items.EMERALD, 3) },
    };

    private static final double FIND_CHANCE = 0.5; // Flat 50% per return trip, per the user's own spec.
    private static final int MIN_FIND_COUNT = 1;
    private static final int MAX_FIND_COUNT = 4;

    private MineLootTable() {
    }

    /** {@code tier} is the 1-based plot Tier (1-5); out-of-range values clamp. Empty if the 50% gate didn't hit. */
    public static Optional<ItemStack> rollLoot(int tier, RandomSource random) {
        if (random.nextDouble() >= FIND_CHANCE) {
            return Optional.empty();
        }
        Entry[] pool = TIERS[Math.max(1, Math.min(TIERS.length, tier)) - 1];
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
