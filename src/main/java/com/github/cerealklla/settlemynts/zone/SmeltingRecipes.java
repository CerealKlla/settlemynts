package com.github.cerealklla.settlemynts.zone;

import java.util.Map;
import java.util.Optional;

import com.github.cerealklla.settlemynts.bridge.LyfeCraftingBridge;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;

/**
 * "Modify the code to recognize the furnace as a valid crafting table, and create recipes which are
 * a 1:1 of the vanilla recipe. E.g. 1 ore + 1 wood = 1 ingot" (explicit user spec, 2026-10-16,
 * following up on confirming the NPC auto-crafting engine had no concept of smelting at all). Reads
 * real vanilla {@link SmeltingRecipe}s directly off the level's own {@code RecipeManager} -- no
 * hand-authored ore/ingot table to drift out of sync with a datapack or another mod's own furnace
 * recipes, same "derived live from the real recipe graph" precedent {@code
 * lyfe.structure.ShopSeedCatalogs}' Grocer catalog already set.
 *
 * <p>Deliberately NOT routed through Lyfe's {@code ServerRecipeStore}/{@code GeneratedRecipe} --
 * smelting is a plain vanilla mechanic Lyfe's crafting overhaul never touches (it only strips/
 * replaces tool/weapon/armor recipes), and a Furnace has no "crafting structure tier" of its own the
 * way Lyfe's four custom crafting blocks do. Reuses {@link LyfeCraftingBridge.RecipeInfo} anyway as
 * the shared "recipe" currency both {@code CraftingExecutor} and {@code PlannedInventoryClearing}
 * already consume -- that record is just plain data (resultId/tier/components), nothing about it
 * actually requires Lyfe to be loaded, and its {@code requirement} field (added alongside this
 * class as {@code requiresFurnace}, widened to a real enum once {@code StonecuttingRecipes} needed a
 * second non-Lyfe source) is what tells the gating logic to check {@link
 * PlotCraftingStructures#hasFurnace} instead of a Lyfe-structure tier for these specific entries.
 *
 * <p><b>Real simplification, explicitly asked for</b>: vanilla's actual furnace mechanic burns fuel
 * at its own rate (one coal smelts 8 items, one plank/stick smelts a fraction of one, lava buckets
 * smelt 100, etc.) with no fixed "1 fuel per smelt" relationship -- modeling that would need a real
 * fuel-value table and a very different consumption shape than this engine's flat "consume these N
 * items, produce M" model supports anywhere else. The user's own spec collapses this to a flat 1:1
 * approximation instead ("1 ore + 1 wood = 1 ingot"), so every recipe here always costs exactly 1 ore
 * (whatever the real smelting recipe's own input item is) + 1 {@link Items#OAK_LOG} (the fixed
 * fuel-stand-in -- same "pick one representative item" precedent {@code
 * blueprynts.bridge.ShopSeedCatalogs}' {@code GenericResource.WOOD} already uses for "any log").
 *
 * <p>Only {@code RecipeType.SMELTING} (a plain Furnace) -- Blast Furnace ({@code BLASTING}) and
 * Smoker ({@code SMOKING}) are separate vanilla recipe types, deliberately out of scope here, per the
 * user's own literal wording ("the furnace").
 */
public final class SmeltingRecipes {

    private static final Identifier WOOD_FUEL_ITEM_ID = BuiltInRegistries.ITEM.getKey(Items.OAK_LOG);

    private SmeltingRecipes() {
    }

    /** The 1:1 smelting-derived recipe for {@code resultId} (an ingot or other furnace output), if any real vanilla Furnace recipe produces it. */
    public static Optional<LyfeCraftingBridge.RecipeInfo> get(ServerLevel level, Identifier resultId) {
        Item resultItem = BuiltInRegistries.ITEM.getOptional(resultId).orElse(null);
        if (resultItem == null) {
            return Optional.empty();
        }
        for (RecipeHolder<?> holder : level.getServer().getRecipeManager().getRecipes()) {
            if (!(holder.value() instanceof SmeltingRecipe recipe)) {
                continue;
            }
            Optional<Holder<Item>> firstInput = recipe.input().items().findFirst();
            if (firstInput.isEmpty()) {
                continue;
            }
            ItemStack result = recipe.assemble(new SingleRecipeInput(new ItemStack(firstInput.get())));
            if (result.isEmpty() || result.getItem() != resultItem) {
                continue;
            }
            Identifier inputId = BuiltInRegistries.ITEM.getKey(firstInput.get().value());
            Map<Identifier, Integer> components = inputId.equals(WOOD_FUEL_ITEM_ID)
                    ? Map.of(inputId, 1) // Shouldn't happen in practice (no vanilla smelting input is a log), but avoid a degenerate 1-entry map collapsing the fuel cost.
                    : Map.of(inputId, 1, WOOD_FUEL_ITEM_ID, 1);
            return Optional.of(new LyfeCraftingBridge.RecipeInfo(resultId, 0, components, Map.of(),
                    LyfeCraftingBridge.RecipeInfo.StructureRequirement.FURNACE));
        }
        return Optional.empty();
    }
}
