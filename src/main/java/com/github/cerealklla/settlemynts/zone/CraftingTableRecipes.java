package com.github.cerealklla.settlemynts.zone;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.github.cerealklla.settlemynts.bridge.LyfeCraftingBridge;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;

/**
 * Real vanilla crafting-table recipes (shaped/shapeless -- a plain {@link CraftingRecipe}, e.g.
 * Lyfe's own "String from Wheat" at {@code data/lyfe/recipe/string_from_wheat.json}) as a craft
 * option for Planned Inventory's buy-vs-craft engine -- added 2026-10-24, real report: "we also have
 * custom craft recipes for cobblestone, gravel, flint, and string too; all should be leveraged when
 * necessary." Before this, {@code PlannedInventoryClearing#resolveRecipes} only ever checked {@link
 * SmeltingRecipes}/{@link StonecuttingRecipes}/{@link LyfeCraftingBridge}'s own generated recipes --
 * a plain vanilla crafting-table recipe (this project's third genuinely distinct recipe shape) was
 * invisible to the whole engine.
 *
 * <p>Ingredient extraction technique confirmed against Yconomics' own {@code price.RecipeIndex} --
 * already-verified-working real API for this exact MC version, not re-derived from scratch here.
 * {@code Recipe#placementInfo().ingredients()} gives one {@link Ingredient} per required slot; this
 * picks each ingredient's first matching item (same accepted simplification {@link
 * StonecuttingRecipes} already uses for its own single-ingredient case -- an OR-set of alternatives
 * collapses to one concrete pick, not modeled as a Lyfe-style named component group). A repeated
 * ingredient across several slots (e.g. three Wheat) merges into one summed count. The result item
 * comes from {@code Recipe#display()}'s {@code SlotDisplay.ItemStackSlotDisplay} -- neither {@code
 * ShapedRecipe} nor {@code ShapelessRecipe} exposes a public bare result accessor in this version.
 *
 * <p>Gated on {@link PlotCraftingStructures#hasCraftingTable} ({@link
 * LyfeCraftingBridge.RecipeInfo.StructureRequirement#CRAFTING_TABLE}) -- a plain vanilla Crafting
 * Table, same "no Lyfe needed, pure vanilla structure" independence {@link SmeltingRecipes}/{@link
 * StonecuttingRecipes} already have from their own Furnace/Stonecutter requirement.
 */
public final class CraftingTableRecipes {

    private CraftingTableRecipes() {
    }

    /** The vanilla crafting-table-derived recipe for {@code resultId}, if any real vanilla crafting recipe produces it. */
    public static Optional<LyfeCraftingBridge.RecipeInfo> get(ServerLevel level, Identifier resultId) {
        Item resultItem = BuiltInRegistries.ITEM.getOptional(resultId).orElse(null);
        if (resultItem == null) {
            return Optional.empty();
        }
        for (RecipeHolder<?> holder : level.getServer().getRecipeManager().getRecipes()) {
            Recipe<?> recipe = holder.value();
            if (!(recipe instanceof CraftingRecipe)) {
                continue;
            }
            ItemStack result = resultOf(recipe);
            if (result == null || result.isEmpty() || result.getItem() != resultItem) {
                continue;
            }
            Map<Identifier, Integer> components = new LinkedHashMap<>();
            for (Ingredient ingredient : recipe.placementInfo().ingredients()) {
                Optional<Holder<Item>> first = ingredient.items().findFirst();
                if (first.isEmpty()) {
                    continue;
                }
                Identifier inputId = BuiltInRegistries.ITEM.getKey(first.get().value());
                components.merge(inputId, 1, Integer::sum);
            }
            if (components.isEmpty()) {
                continue; // No real ingredient cost to derive from (e.g. a special-crafting recipe).
            }
            return Optional.of(new LyfeCraftingBridge.RecipeInfo(resultId, 0, components, Map.of(),
                    LyfeCraftingBridge.RecipeInfo.StructureRequirement.CRAFTING_TABLE, 0, result.getCount(), Optional.empty(), 0));
        }
        return Optional.empty();
    }

    private static ItemStack resultOf(Recipe<?> recipe) {
        for (RecipeDisplay display : recipe.display()) {
            if (display.result() instanceof SlotDisplay.ItemStackSlotDisplay itemStackSlot) {
                return itemStackSlot.stack().create();
            }
        }
        return null;
    }
}
