package com.github.cerealklla.settlemynts.bridge;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.github.cerealklla.lyfe.api.Lyfe;
import com.github.cerealklla.lyfe.craft.CraftingStructureBlockEntity;
import com.github.cerealklla.lyfe.craft.GeneratedRecipe;
import com.github.cerealklla.lyfe.craft.ServerRecipeStore;

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.fml.ModList;

/**
 * NPC plot crafting (design doc Section 14a, added 2026-10-09, explicit user request: "a Lumberjack
 * might take logs and turn them into Planks... a Blacksmith may purchase ingots... and turn them into
 * Armor and Weapons") -- reads Lyfe's server-scoped generated recipes and resolves generic ingredient
 * groups, so {@code zone.CraftingExecutor}/{@code zone.PlannedInventoryClearing} can decide what a
 * plot can craft and from what, with no live player or menu involved anywhere in the path. Optional
 * soft dependency, same isolation convention as every other bridge in this package -- every call site
 * elsewhere in Settlemynts must check {@link #isLoaded()} itself before touching this class at all.
 */
public final class LyfeCraftingBridge {

    private LyfeCraftingBridge() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded("lyfe");
    }

    /**
     * Local mirror of {@code craft.GeneratedRecipe} -- see {@code zone.ShopResource}'s own class doc
     * for why a Yconomics/Lyfe type never appears in a signature outside its bridge. {@code
     * requiresFurnace} (added 2026-10-16, explicit user request: "recognize the furnace as a valid
     * crafting table... 1:1 of the vanilla recipe") -- {@code false} for every real Lyfe-sourced
     * recipe below; {@code true} only for {@code zone.SmeltingRecipes}' own entries, which also
     * reuses this exact record type as its "recipe" currency even though it has nothing to do with
     * Lyfe -- see that class's own doc for why gating by this flag instead of {@code tier} was
     * necessary (a plain vanilla Furnace has no "Lyfe crafting structure tier" of its own at all).
     */
    public record RecipeInfo(Identifier resultId, int tier, Map<Identifier, Integer> specificComponents,
                              Map<String, Integer> genericComponents, boolean requiresFurnace) {

        /** Whether a plot with this much Lyfe crafting-structure tier and/or a Furnace can craft this recipe. */
        public boolean craftableWith(int structureTier, boolean hasFurnace) {
            return requiresFurnace ? hasFurnace : structureTier >= tier;
        }
    }

    public static Optional<RecipeInfo> getRecipe(Identifier resultId) {
        return ServerRecipeStore.get(resultId).map(LyfeCraftingBridge::toLocal);
    }

    public static List<RecipeInfo> getAllRecipes() {
        return ServerRecipeStore.all().values().stream().map(LyfeCraftingBridge::toLocal).toList();
    }

    public static List<Identifier> componentGroupMembers(String groupName) {
        return Lyfe.componentGroupMembers(groupName);
    }

    /** {@code 0} if {@code be} isn't a Lyfe crafting structure (or isn't loaded), else its real tier. */
    public static int craftingStructureTier(BlockEntity be) {
        return be instanceof CraftingStructureBlockEntity structure ? structure.tier() : 0;
    }

    private static RecipeInfo toLocal(GeneratedRecipe recipe) {
        return new RecipeInfo(recipe.resultId(), recipe.tier(), recipe.specificComponents(), recipe.genericComponents(), false);
    }
}
