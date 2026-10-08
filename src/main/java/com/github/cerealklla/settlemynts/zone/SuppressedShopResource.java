package com.github.cerealklla.settlemynts.zone;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;

/**
 * One resource the plot owner has explicitly set to 0/blank and saved on {@code
 * client.ManageShopScreen} -- part of the plot's persisted Shop Config (design spec, 2026-10-08):
 * "Setting a price to 0 or blank will ensure it does not show up in the Shop screen... if a previous
 * row was blank/0, then it should still be blank/0, so the player doesn't have to re-ignore items
 * every single time they try to manage the shop."
 *
 * <p>Needed because a real Yconomics {@code shop.ShopListing} structurally can't represent a 0-price
 * row -- its constructor floor-clamps to {@code shop.ShopPricing#MIN_SELL_PRICE}, and {@code
 * removeListing} deletes the row outright, leaving no trace it was ever explicitly excluded rather
 * than simply never configured. This record is that trace, persisted on {@link PlotRecord} alongside
 * (not inside) the real listings. {@code client.ManageShopScreen}'s row assembly treats "has a real
 * listing" and "is in this set" as the two ways a resource counts as "already configured" -- only a
 * resource in neither state gets a catalog-suggested default price shown.
 */
public record SuppressedShopResource(Identifier resourceKey, boolean isTag) {

    public static final Codec<SuppressedShopResource> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("resource_key").forGetter(SuppressedShopResource::resourceKey),
            Codec.BOOL.fieldOf("is_tag").forGetter(SuppressedShopResource::isTag)
    ).apply(i, SuppressedShopResource::new));
}
