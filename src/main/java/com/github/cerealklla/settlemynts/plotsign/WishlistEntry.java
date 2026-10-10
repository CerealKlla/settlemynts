package com.github.cerealklla.settlemynts.plotsign;

import java.util.Arrays;
import java.util.stream.Collectors;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * One row of {@code client.WishlistScreen} (2026-10-10) -- a shop's public "what do I still need"
 * list, see {@code zone.ShopWishlist}'s own doc for the full feature. Mirrors {@link
 * ShopListingEntry}'s own label/matches shape exactly, minus the stock/tag-variant fields that don't
 * apply here (a Planned Inventory target is never a quality variant). {@code normalPricePerUnit} is
 * the Zone Type catalog's own suggested price for this resource (the same baseline the midnight
 * auto-buy effectively pays at); {@code premiumPricePerUnit} is {@code
 * zone.ShopWishlist#premiumPrice} of it -- what a direct, player-initiated sale through this screen
 * actually pays, always strictly higher.
 */
public record WishlistEntry(Identifier resourceKey, boolean isTag, int quantityNeeded, int normalPricePerUnit, int premiumPricePerUnit) {

    public static final Codec<WishlistEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("resource_key").forGetter(WishlistEntry::resourceKey),
            Codec.BOOL.fieldOf("is_tag").forGetter(WishlistEntry::isTag),
            Codec.INT.fieldOf("quantity_needed").forGetter(WishlistEntry::quantityNeeded),
            Codec.INT.fieldOf("normal_price_per_unit").forGetter(WishlistEntry::normalPricePerUnit),
            Codec.INT.fieldOf("premium_price_per_unit").forGetter(WishlistEntry::premiumPricePerUnit)
    ).apply(i, WishlistEntry::new));

    /** Same translated-name/"Any &lt;Tag&gt;" convention as {@link ShopListingEntry#label()}. */
    public String label() {
        if (isTag) {
            String readable = Arrays.stream(resourceKey.getPath().replace('_', ' ').split(" "))
                    .map(w -> w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1))
                    .collect(Collectors.joining(" "));
            return "Any " + readable;
        }
        Item item = BuiltInRegistries.ITEM.getValue(resourceKey);
        return item != Items.AIR ? new ItemStack(item).getHoverName().getString() : resourceKey.toString();
    }

    /** Used client-side to compute the player's own stock of this resource without a round trip. */
    public boolean matches(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (isTag) {
            return stack.is(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, resourceKey));
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(resourceKey);
    }
}
