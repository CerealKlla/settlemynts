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
 * A client-facing view of one Shop listing -- the network/UI shape, not Yconomics'/{@code
 * zone.ShopResource}'s own types, so {@code client.ShopScreen} needs no cross-mod type reference at
 * all. {@code isTag} distinguishes a whole category ({@code resourceKey} is a tag id) from one
 * specific item ({@code resourceKey} is an item id) -- same split as {@code zone.ShopResource}.
 */
public record ShopListingEntry(Identifier resourceKey, boolean isTag, int pricePerUnit) {

    public static final Codec<ShopListingEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("resource_key").forGetter(ShopListingEntry::resourceKey),
            Codec.BOOL.fieldOf("is_tag").forGetter(ShopListingEntry::isTag),
            Codec.INT.fieldOf("price_per_unit").forGetter(ShopListingEntry::pricePerUnit)
    ).apply(i, ShopListingEntry::new));

    /**
     * A player-facing label -- the item's real translated display name (e.g. "Cooked Beef") for a
     * plain item, or a readable "Any &lt;Tag Name&gt;" for a tag (real report, 2026-10-05: shops were
     * showing raw ids like "minecraft:cooked_beef" instead of a nice name). Safe to call client-side
     * only -- relies on {@code Item#getDescription()}'s translation resolving against the client's
     * own loaded language, same as every other in-game item name.
     */
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
}
