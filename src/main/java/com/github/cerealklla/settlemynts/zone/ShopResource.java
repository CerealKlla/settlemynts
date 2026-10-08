package com.github.cerealklla.settlemynts.zone;

import java.util.Optional;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Settlemynts' own local mirror of Yconomics' {@code shop.ShopResource} (design doc Section 14a,
 * added 2026-10-05) -- kept as a separate type, not a direct reference to the Yconomics class,
 * since {@code api.Settlemynts} (this mirror's main consumer) is loaded unconditionally even on a
 * Yconomics-less server, and this suite's established convention (see {@code
 * bridge.YconomicsBillBridge}'s own class doc) is that a Yconomics type never appears in a
 * signature outside a bridge class gated behind {@code ModList.get().isLoaded("yconomics")}.
 * {@code bridge.YconomicsShopBridge} converts between this and the real Yconomics type.
 */
public record ShopResource(Optional<TagKey<Item>> tag, Optional<Identifier> itemId) {

    public static ShopResource ofTag(TagKey<Item> tag) {
        return new ShopResource(Optional.of(tag), Optional.empty());
    }

    public static ShopResource ofItem(Identifier itemId) {
        return new ShopResource(Optional.empty(), Optional.of(itemId));
    }

    /** Added 2026-10-08 for "Sell N" -- does {@code stack} fall under this listing's resource? Mirrors Yconomics' own {@code shop.ShopResource#matches} exactly. */
    public boolean matches(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (tag.isPresent()) {
            return stack.is(tag.get());
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(itemId.get());
    }
}
