package com.github.cerealklla.settlemynts.bridge;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.github.cerealklla.lyfe.api.Lyfe;
import com.github.cerealklla.lyfe.cook.CookingListener;
import com.github.cerealklla.lyfe.cook.CookingStructureBlockEntity;
import com.github.cerealklla.lyfe.cook.GeneratedFoodRecipe;
import com.github.cerealklla.lyfe.cook.ServerFoodRecipeStore;
import com.github.cerealklla.lyfe.craft.CraftingStructureBlockEntity;
import com.github.cerealklla.lyfe.craft.GeneratedRecipe;
import com.github.cerealklla.lyfe.craft.ServerRecipeStore;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
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
     * a single boolean too narrow) -- {@code LYFE_STRUCTURE} for every real Lyfe tool/weapon/armor
     * recipe below; {@code COOKING_STRUCTURE} for a Lyfe {@code cook.GeneratedFoodRecipe} (gated by
     * its own separate tier ladder -- a Cooking Station/Grill/Stove/Oven/Chef Set is a structurally
     * distinct block entity from a Lyfe crafting structure, see {@code
     * zone.PlotCraftingStructures#maxCookingStructureTier}); {@code FURNACE}/{@code STONECUTTER} only
     * for {@code zone.SmeltingRecipes}/{@code zone.StonecuttingRecipes}' own entries, which also reuse
     * this exact record type as their "recipe" currency even though neither has anything to do with
     * Lyfe -- see those classes' own doc for why gating by a plain vanilla structure's presence, not
     * {@code tier}, was necessary (a Furnace/Stonecutter has no "Lyfe crafting structure tier" of its
     * own at all).
     */
    public record RecipeInfo(Identifier resultId, int tier, Map<Identifier, Integer> specificComponents,
                              Map<String, Integer> genericComponents, StructureRequirement requirement) {

        public enum StructureRequirement { LYFE_STRUCTURE, COOKING_STRUCTURE, FURNACE, STONECUTTER }

        /** Whether {@code capability} is enough to craft this recipe. */
        public boolean craftableWith(com.github.cerealklla.settlemynts.zone.PlotCraftingCapability capability) {
            return switch (requirement) {
                case LYFE_STRUCTURE -> capability.lyfeStructureTier() >= tier;
                case COOKING_STRUCTURE -> capability.cookingStructureTier() >= tier;
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

    /**
     * The Lyfe-generated cooking recipe for {@code resultId} (a cooked-food item), if any -- separate
     * from {@link #getRecipe} since Lyfe's cooking overhaul (2026-10-03) keeps food recipes in its own
     * {@code cook.ServerFoodRecipeStore}, distinct from the tool/weapon/armor {@code
     * craft.ServerRecipeStore}. Added 2026-10-17: Lyfe's {@code VanillaFoodRecipeStripper} deletes the
     * real vanilla {@link net.minecraft.world.item.crafting.SmeltingRecipe}s for every Track A cooked
     * food (cooked beef/porkchop/chicken/mutton/rabbit/cod/salmon, baked potato, ...) and replaces them
     * with its own generated recipes here -- without this, {@code zone.SmeltingRecipes} (which only
     * ever reads the live vanilla {@code RecipeManager}) finds nothing at all for those items on a
     * Lyfe-loaded server, even with a real Furnace or Cooking Station present.
     */
    public static Optional<RecipeInfo> getFoodRecipe(Identifier resultId) {
        return ServerFoodRecipeStore.get(resultId).map(LyfeCraftingBridge::toLocal);
    }

    /**
     * Bakes real icons/nutrition/a quality-prefixed custom name onto {@code result} -- the same
     * {@code cook.CookingListener#bakeIcons} call a player's own "Cook" button makes -- so an
     * NPC-crafted dish is a genuine valued item in Lyfe's cooking system instead of a plain vanilla
     * stack with vanilla's own flat nutrition. Added 2026-10-17: real report, screenshot showing a
     * plain "Cooked Porkchop" with no food-value prefix -- {@code zone.CraftingExecutor#craftOne} was
     * depositing a bare {@code new ItemStack(item)} for every recipe source including food, since
     * baking was never wired up when {@link #getFoodRecipe} was added. No Cook-skill bonus is applied
     * (there's no player here) -- same "NPC-crafted output skips the player-skill-flavored bonus"
     * precedent {@link #craftingStructureTier} already set for Quality on tools/armor.
     */
    public static void bakeFoodIcons(ItemStack result, int structureTier, int componentsUsed) {
        double icons = CookingListener.computeIcons(structureTier, 0, componentsUsed);
        CookingListener.bakeIcons(result, icons, structureTier);
    }

    public static List<Identifier> componentGroupMembers(String groupName) {
        return Lyfe.componentGroupMembers(groupName);
    }

    /** {@code 0} if {@code be} isn't a Lyfe crafting structure (or isn't loaded), else its real tier. */
    public static int craftingStructureTier(BlockEntity be) {
        return be instanceof CraftingStructureBlockEntity structure ? structure.tier() : 0;
    }

    /** {@code 0} if {@code be} isn't a Lyfe cooking structure (or isn't loaded), else its real tier. */
    public static int cookingStructureTier(BlockEntity be) {
        return be instanceof CookingStructureBlockEntity structure ? structure.tier() : 0;
    }

    private static RecipeInfo toLocal(GeneratedRecipe recipe) {
        return new RecipeInfo(recipe.resultId(), recipe.tier(), recipe.specificComponents(), recipe.genericComponents(), RecipeInfo.StructureRequirement.LYFE_STRUCTURE);
    }

    private static RecipeInfo toLocal(GeneratedFoodRecipe recipe) {
        return new RecipeInfo(recipe.resultId(), recipe.tier(), recipe.components(), Map.of(), RecipeInfo.StructureRequirement.COOKING_STRUCTURE);
    }
}
