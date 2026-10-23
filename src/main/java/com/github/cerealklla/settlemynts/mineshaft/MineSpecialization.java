package com.github.cerealklla.settlemynts.mineshaft;

/**
 * A Mineshaft plot's loot-table focus (added 2026-10-23, explicit user request) -- the Owner/Mayor/
 * Town Planners can switch a Mineshaft plot between {@link #ORE} (the original, already-live table:
 * mostly Cobblestone/Coal with raw ores climbing by Tier) and {@link #STONE} (new: mostly the "Any
 * Stone" variety set -- Granite/Diorite/Andesite/Tuff/Calcite/Deepslate, plus Gravel/Flint and, at
 * Tier 5, Amethyst Shard -- with the same raw ores still reachable, just at much lower weight). See
 * {@link MineLootTable}'s own doc for both tables. Defaults to {@link #ORE} -- every pre-existing
 * saved Mineshaft plot keeps its current behavior unless its manager explicitly switches it.
 */
public enum MineSpecialization {
    ORE, STONE;

    public String label() {
        return this == ORE ? "Ore" : "Stone";
    }
}
