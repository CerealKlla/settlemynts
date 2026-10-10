package com.github.cerealklla.settlemynts.plotsign;

import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;

/**
 * One row's edit on {@code client.ManageShopScreen}'s "Save Changes" -- part of {@link
 * SetShopListingsPayload}'s batch. {@code price <= 0} means "remove this listing if one exists" (the
 * row's price box was left blank/zeroed); {@code price > 0} means "set/create the listing at this
 * exact price" (floor/never-equal-buy-sell enforcement happens server-side, inside {@code
 * shop.ShopListing}'s own constructor, same as every other price-setting path). {@code customName}
 * (added 2026-10-10, per-quality Shop listings) identifies one exact crafted-quality variant --
 * empty for a plain listing, same convention as every other Shop payload's own field.
 */
public record ShopListingUpdate(Identifier resourceKey, boolean isTag, Optional<String> customName, int price) {

    public static final Codec<ShopListingUpdate> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("resource_key").forGetter(ShopListingUpdate::resourceKey),
            Codec.BOOL.fieldOf("is_tag").forGetter(ShopListingUpdate::isTag),
            Codec.STRING.optionalFieldOf("custom_name").forGetter(ShopListingUpdate::customName),
            Codec.INT.fieldOf("price").forGetter(ShopListingUpdate::price)
    ).apply(i, ShopListingUpdate::new));
}
