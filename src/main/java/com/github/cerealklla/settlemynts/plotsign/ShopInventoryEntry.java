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
 * One row of {@code client.ManageShopScreen} (2026-10-08, replacing the old "hold the item, close
 * this menu, walk to the sign, click Manage, click Add Listing (Held Item)" flow -- real feedback:
 * that round trip was clunky). Unlike {@link ShopListingEntry}, this covers every unique item
 * actually sitting in the plot's own boxes right now (via {@code api.Settlemynts#scanPlotItemStock}),
 * not just the ones already listed -- plus any existing listing (concrete or tag-based) that isn't
 * backed by current box stock, so an owner can still see/edit a listing even if its boxes are
 * temporarily empty. {@code listedPrice} of {@code 0} means "not currently listed" -- the Manage
 * screen renders that as a blank price box, and typing {@code 0}/leaving it blank on Save removes any
 * existing listing for this resource (see {@code SetShopListingsPayload}'s own doc).
 */
public record ShopInventoryEntry(Identifier resourceKey, boolean isTag, int shopStock, int listedPrice) {

    public static final Codec<ShopInventoryEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("resource_key").forGetter(ShopInventoryEntry::resourceKey),
            Codec.BOOL.fieldOf("is_tag").forGetter(ShopInventoryEntry::isTag),
            Codec.INT.fieldOf("shop_stock").forGetter(ShopInventoryEntry::shopStock),
            Codec.INT.fieldOf("listed_price").forGetter(ShopInventoryEntry::listedPrice)
    ).apply(i, ShopInventoryEntry::new));

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
}
