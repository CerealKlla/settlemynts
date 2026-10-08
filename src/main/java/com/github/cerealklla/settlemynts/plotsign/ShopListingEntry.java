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
 * {@code buyPricePerUnit} (added 2026-10-08) is what the shop pays to buy this item back -- always
 * {@code shop.ShopPricing#deriveBuyPrice(pricePerUnit)}, computed server-side so the client never
 * needs its own copy of that formula. {@code shopStock} (added 2026-10-08) is how many units of this
 * resource currently sit in the plot's own boxes -- the "Shop Stock" column on the Buy-mode table,
 * computed server-side via {@code api.Settlemynts#scanPlotItemStock} since the client can't read box
 * contents that aren't its own.
 *
 * <p>{@code effectiveBuyPrice}/{@code effectiveSellPrice} (added 2026-10-08, real question: "are the
 * prices listed in here after being modified by player merchant skills or before?") -- {@code
 * pricePerUnit}/{@code buyPricePerUnit} are always the raw listing prices, with no per-player
 * Merchant-skill bonus applied (that bonus is computed as a rebate/bonus at the moment of the real
 * transaction, see {@code SettlemyntsMod#buyFromShop}/{@code #sellToShop}'s own comments for why).
 * These two fields are what *this specific requesting player* would actually pay/receive right now,
 * computed server-side the same way via {@code bridge.LyfeMerchantBridge#getPriceBonusFraction} and
 * {@code bridge.YconomicsShopBridge#effectiveBuyCost}/{@code #effectiveSellPayout} -- equal to the
 * raw price whenever Lyfe isn't loaded or this player's Merchant bonus is currently zero.
 */
public record ShopListingEntry(Identifier resourceKey, boolean isTag, int pricePerUnit, int buyPricePerUnit, int shopStock,
                                int effectiveBuyPrice, int effectiveSellPrice) {

    public static final Codec<ShopListingEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("resource_key").forGetter(ShopListingEntry::resourceKey),
            Codec.BOOL.fieldOf("is_tag").forGetter(ShopListingEntry::isTag),
            Codec.INT.fieldOf("price_per_unit").forGetter(ShopListingEntry::pricePerUnit),
            Codec.INT.fieldOf("buy_price_per_unit").forGetter(ShopListingEntry::buyPricePerUnit),
            Codec.INT.fieldOf("shop_stock").forGetter(ShopListingEntry::shopStock),
            Codec.INT.fieldOf("effective_buy_price").forGetter(ShopListingEntry::effectiveBuyPrice),
            Codec.INT.fieldOf("effective_sell_price").forGetter(ShopListingEntry::effectiveSellPrice)
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

    /** Does {@code stack} fall under this listing -- used client-side to compute the "Player Stock" column without a round trip. */
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
