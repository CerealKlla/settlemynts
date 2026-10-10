package com.github.cerealklla.settlemynts.plotsign;

import java.util.Arrays;
import java.util.Optional;
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
 * screen renders that as a blank price box (falling back to {@code suggestedPrice} if nonzero), and
 * typing {@code 0}/leaving it blank on Save removes any existing listing for this resource (see
 * {@code SetShopListingsPayload}'s own doc). {@code suggestedPrice} (added 2026-10-08, replacing the
 * old always-on catalog auto-listing -- see {@code zone.ShopSeeding#applyCatalog}'s own doc for the
 * real bug this fixed) is a pure UI hint, never a real price: {@code zone.ShopSeeding#suggestedPriceFor}'s
 * catalog recommendation for a never-yet-listed resource, shown only as the Sell Cost box's initial
 * value so a brand-new item isn't blank with no guidance -- it has no effect at all once a real
 * listing exists ({@code listedPrice > 0}) or once the owner has explicitly saved it blank.
 */
public record ShopInventoryEntry(Identifier resourceKey, boolean isTag, Optional<String> customName, int shopStock, int listedPrice, int suggestedPrice) {

    public static final Codec<ShopInventoryEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("resource_key").forGetter(ShopInventoryEntry::resourceKey),
            Codec.BOOL.fieldOf("is_tag").forGetter(ShopInventoryEntry::isTag),
            Codec.STRING.optionalFieldOf("custom_name").forGetter(ShopInventoryEntry::customName),
            Codec.INT.fieldOf("shop_stock").forGetter(ShopInventoryEntry::shopStock),
            Codec.INT.fieldOf("listed_price").forGetter(ShopInventoryEntry::listedPrice),
            Codec.INT.fieldOf("suggested_price").forGetter(ShopInventoryEntry::suggestedPrice)
    ).apply(i, ShopInventoryEntry::new));

    /**
     * Same translated-name/"Any &lt;Tag&gt;" convention as {@link ShopListingEntry#label()} -- except
     * {@code customName} (2026-10-10, per-quality Shop listings), when present, *is* the label
     * verbatim (Lyfe's baked {@code "[3.75] (T5) - Bread"}, not the plain item name), since that's
     * the whole point of a per-quality row: distinguishing it from every other quality of the same
     * item at a glance.
     */
    public String label() {
        if (customName.isPresent()) {
            return customName.get();
        }
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
