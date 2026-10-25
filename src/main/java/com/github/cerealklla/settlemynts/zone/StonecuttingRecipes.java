package com.github.cerealklla.settlemynts.zone;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.github.cerealklla.settlemynts.bridge.LyfeCraftingBridge;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.StonecutterRecipe;

/**
 * "Can we do the same fix for that which we did for the furnace?" (explicit user follow-up,
 * 2026-10-16, after confirming the Stonemason's own Shop catalog -- stone, smooth stone, stone
 * bricks, chiseled stone bricks, polished deepslate -- had nothing actually crafting it; there's no
 * dedicated Stonemason worker NPC at all, unlike Farmer/Lumberjack/Miner, and the auto-crafting
 * engine didn't recognize a Stonecutter any more than it recognized a Furnace before that fix).
 * Mirrors {@link SmeltingRecipes} exactly -- reads real vanilla {@link StonecutterRecipe}s directly
 * off the level's own {@code RecipeManager}, same "derived live from the real recipe graph"
 * precedent, same reason this stays independent of Lyfe's {@code ServerRecipeStore} (a Stonecutter
 * has no "crafting structure tier" either).
 *
 * <p><b>Genuinely simpler than smelting</b>: a real vanilla Stonecutter recipe has no second
 * ingredient at all (no fuel-equivalent concept) -- every entry here is a true 1:1,
 * {@code {inputId: 1} -> resultId}, exactly matching the real vanilla recipe with no approximation
 * needed. Where vanilla has more than one real Stonecutter recipe producing the same output (rare,
 * but e.g. both {@code stone} and {@code cobblestone} can each cut into certain variants depending on
 * the exact recipe set), whichever one {@code RecipeManager#getRecipes()} happens to iterate first
 * wins -- same accepted non-determinism {@link SmeltingRecipes} already has.
 */
public final class StonecuttingRecipes {

    private StonecuttingRecipes() {
    }

    // Real crash, 2026-10-24 (watchdog: 60.01s single tick, stack bottoming out here): this used to
    // scan the server's ENTIRE RecipeManager from scratch on every single call, same bug
    // SmeltingRecipes#buildCache's own doc already describes fixing once (2026-10-20) -- this file
    // was never given the same fix at the time, and PlannedInventoryClearing's unmemoized-per-call
    // recursive buy-vs-craft search (cheapestCraftOption/estimateCraftCost/cheapestCostForOneUnit)
    // calls this once per ingredient at every recursion level, same mechanism, same precedent, same
    // fix: a cache keyed by the RecipeManager's own identity, rebuilt for free on every data reload.
    private static RecipeManager cachedManager;
    private static Map<Identifier, LyfeCraftingBridge.RecipeInfo> cache = Map.of();

    /** The 1:1 stonecutting-derived recipe for {@code resultId}, if any real vanilla Stonecutter recipe produces it. */
    public static Optional<LyfeCraftingBridge.RecipeInfo> get(ServerLevel level, Identifier resultId) {
        RecipeManager manager = level.getServer().getRecipeManager();
        if (manager != cachedManager) {
            cachedManager = manager;
            cache = buildCache(manager);
        }
        return Optional.ofNullable(cache.get(resultId));
    }

    private static Map<Identifier, LyfeCraftingBridge.RecipeInfo> buildCache(RecipeManager manager) {
        Map<Identifier, LyfeCraftingBridge.RecipeInfo> result = new HashMap<>();
        for (RecipeHolder<?> holder : manager.getRecipes()) {
            if (!(holder.value() instanceof StonecutterRecipe recipe)) {
                continue;
            }
            Optional<Holder<Item>> firstInput = recipe.input().items().findFirst();
            if (firstInput.isEmpty()) {
                continue;
            }
            ItemStack resultStack = recipe.assemble(new SingleRecipeInput(new ItemStack(firstInput.get())));
            if (resultStack.isEmpty()) {
                continue;
            }
            Identifier resultId = BuiltInRegistries.ITEM.getKey(resultStack.getItem());
            if (result.containsKey(resultId)) {
                continue; // First matching recipe wins -- same semantics the old scan-and-return-first had.
            }
            Identifier inputId = BuiltInRegistries.ITEM.getKey(firstInput.get().value());
            result.put(resultId, new LyfeCraftingBridge.RecipeInfo(resultId, 0, Map.of(inputId, 1), Map.of(),
                    LyfeCraftingBridge.RecipeInfo.StructureRequirement.STONECUTTER, 0));
        }
        return result;
    }
}
