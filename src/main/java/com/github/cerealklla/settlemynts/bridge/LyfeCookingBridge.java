package com.github.cerealklla.settlemynts.bridge;

import java.util.Optional;

import com.github.cerealklla.lyfe.api.Lyfe;

import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

/**
 * Reads Lyfe's baked crafted-food quality off a stack (2026-10-10, for per-quality Shop listings --
 * see {@code zone.ShopResource#ofExactItem}'s own doc) -- optional soft dependency, same isolation
 * convention as the other bridges in this package.
 */
public final class LyfeCookingBridge {

    private LyfeCookingBridge() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded("lyfe");
    }

    /** The exact icons value baked into {@code stack}, or empty if it's not a Lyfe crafted-food item. */
    public static Optional<Double> getIcons(ItemStack stack) {
        return Lyfe.getCraftedFoodIcons(stack);
    }
}
