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
     * requirement} (added 2026-10-16 as {@code requiresFurnace}, a plain boolean; widened to this enum
     * the same day once a second vanilla-structure source -- {@code zone.StonecuttingRecipes} -- made
     * a single boolean too narrow) -- {@code LYFE_STRUCTURE} for every real Lyfe-sourced recipe below;
     * {@code FURNACE}/{@code STONECUTTER} only for {@code zone.SmeltingRecipes}/{@code
     * zone.StonecuttingRecipes}' own entries, which also reuse this exact record type as their
     * "recipe" currency even though neither has anything to do with Lyfe -- see those classes' own
     * doc for why gating by a plain vanilla structure's presence, not {@code tier}, was necessary (a
     * Furnace/Stonecutter has no "Lyfe crafting structure tier" of its own at all).
     */
    public record RecipeInfo(Identifier resultId, int tier, Map<Identifier, Integer> specificComponents,
                              Map<String, Integer> genericComponents, StructureRequirement requirement) {

        public enum StructureRequirement { LYFE_STRUCTURE, FURNACE, STONECUTTER }

        /** Whether {@code capability} is enough to craft this recipe. */
        public boolean craftableWith(com.github.cerealklla.settlemynts.zone.PlotCraftingCapability capability) {
            return switch (requirement) {
                case LYFE_STRUCTURE -> capability.lyfeStructureTier() >= tier;
                case FURNACE -> capability.hasFurnace();
                case STONECUTTER -> capability.hasStonecutter();
            };
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
        return new RecipeInfo(recipe.resultId(), recipe.tier(), recipe.specificComponents(), recipe.genericComponents(), RecipeInfo.StructureRequirement.LYFE_STRUCTURE);
    }
}
