package com.github.cerealklla.settlemynts.zone;

import java.util.Optional;
import java.util.UUID;

import com.github.cerealklla.cartographyr.geo.Geometry;
import com.github.cerealklla.settlemynts.api.Settlemynts;

import net.minecraft.server.level.ServerLevel;

/**
 * Bundles everything a plot can craft with -- its highest-Tier Lyfe crafting structure plus whichever
 * plain vanilla utility blocks ({@code zone.SmeltingRecipes}' Furnace, {@code
 * zone.StonecuttingRecipes}' Stonecutter, ...) are also physically present. Added 2026-10-16
 * alongside the Stonecutter fix, replacing a pair of bare {@code int buyerTier}/{@code boolean
 * hasFurnace} parameters that {@code PlannedInventoryClearing} used to thread through every
 * craft-cost-estimation/execution method by hand -- a second vanilla-structure requirement
 * (Stonecutter) made that shape genuinely awkward to extend, and a future third one would only make
 * it worse. One object threaded everywhere instead; adding a new structure type later is just one
 * more field here plus one more {@code LyfeCraftingBridge.RecipeInfo.StructureRequirement} case,
 * no signature changes anywhere else -- proven out again 2026-10-17 adding {@code
 * cookingStructureTier} (a Lyfe Cooking Station/Grill/Stove/Oven/Chef Set, structurally distinct
 * from a Lyfe crafting structure) for Lyfe's cooking recipes.
 */
public record PlotCraftingCapability(int lyfeStructureTier, int cookingStructureTier, boolean hasFurnace, boolean hasStonecutter) {

    public static final PlotCraftingCapability NONE = new PlotCraftingCapability(0, 0, false, false);

    public static PlotCraftingCapability resolve(ServerLevel level, UUID plotId) {
        Optional<Geometry.Polygon> polygon = Settlemynts.resolvePlotPolygon(level, plotId);
        return polygon.map(area -> resolve(level, area)).orElse(NONE);
    }

    /** Same as {@link #resolve(ServerLevel, UUID)}, for a polygon the caller already has in hand -- avoids its own world scan. */
    public static PlotCraftingCapability resolve(ServerLevel level, Geometry.Polygon area) {
        return new PlotCraftingCapability(
                PlotCraftingStructures.maxCraftingStructureTier(level, area),
                PlotCraftingStructures.maxCookingStructureTier(level, area),
                PlotCraftingStructures.hasFurnace(level, area),
                PlotCraftingStructures.hasStonecutter(level, area));
    }
}
