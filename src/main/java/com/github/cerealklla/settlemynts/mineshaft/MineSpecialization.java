package com.github.cerealklla.settlemynts.mineshaft;

/**
 * A Mineshaft plot's loot-table focus (added 2026-10-23, explicit user request) -- the Owner/Mayor/
 * Town Planners can switch a Mineshaft plot between {@link #ORE} (the original, already-live table:
 * mostly Cobblestone/Coal with raw ores climbing by Tier), {@link #STONE} (mostly the "Any Stone"
 * variety set -- Granite/Diorite/Andesite/Tuff/Calcite/Deepslate, plus, at Tier 5, Amethyst Shard),
 * and {@link #EXCAVATION} (added 2026-10-23, same day -- shovel-digging focus: Dirt/Gravel/Sand/
 * Clay/Flint, Gravel/Flint having moved here from Stone since they're a shovel's own domain). See
 * {@link MineLootTable}'s own doc for all three tables and the three-way trace-weight symmetry
 * between them. Defaults to {@link #ORE} -- every pre-existing saved Mineshaft plot keeps its
 * current behavior unless its manager explicitly switches it.
 */
public enum MineSpecialization {
    ORE, STONE, EXCAVATION;

    public String label() {
        return switch (this) {
            case ORE -> "Ore";
            case STONE -> "Stone";
            case EXCAVATION -> "Excavation";
        };
    }
}
