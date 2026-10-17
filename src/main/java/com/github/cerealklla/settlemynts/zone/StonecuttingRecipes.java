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
import net.minecraft.world.item.crafting.RecipeHolder;
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

    /** The 1:1 stonecutting-derived recipe for {@code resultId}, if any real vanilla Stonecutter recipe produces it. */
    public static Optional<LyfeCraftingBridge.RecipeInfo> get(ServerLevel level, Identifier resultId) {
        Item resultItem = BuiltInRegistries.ITEM.getOptional(resultId).orElse(null);
        if (resultItem == null) {
            return Optional.empty();
        }
        for (RecipeHolder<?> holder : level.getServer().getRecipeManager().getRecipes()) {
            if (!(holder.value() instanceof StonecutterRecipe recipe)) {
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
            return Optional.of(new LyfeCraftingBridge.RecipeInfo(resultId, 0, Map.of(inputId, 1), Map.of(),
                    LyfeCraftingBridge.RecipeInfo.StructureRequirement.STONECUTTER));
        }
        return Optional.empty();
    }
}
