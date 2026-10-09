package com.github.cerealklla.settlemynts.plotsign;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;

/**
 * One row's edit on {@code client.PlannedInventoryScreen}'s "Save Changes" -- part of {@link
 * SetPlannedInventoryPayload}'s batch. {@code managed == false} means "this resource isn't tracked by
 * Planned Inventory" (the row's EditBox was left blank) -- no {@code zone.PlannedInventoryTarget} is
 * kept for it. {@code managed == true} sets/creates a target at exactly {@code targetCount}, which may
 * legitimately be {@code 0} (a deliberate "I want none of this on hand" target, not a removal signal --
 * see {@code zone.PlannedInventoryTarget}'s own class doc for why this differs from {@link
 * ShopListingUpdate}'s 0-means-delete convention).
 */
public record PlannedInventoryUpdate(Identifier resourceKey, boolean isTag, boolean managed, int targetCount) {

    public static final Codec<PlannedInventoryUpdate> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("resource_key").forGetter(PlannedInventoryUpdate::resourceKey),
            Codec.BOOL.fieldOf("is_tag").forGetter(PlannedInventoryUpdate::isTag),
            Codec.BOOL.fieldOf("managed").forGetter(PlannedInventoryUpdate::managed),
            Codec.INT.fieldOf("target_count").forGetter(PlannedInventoryUpdate::targetCount)
    ).apply(i, PlannedInventoryUpdate::new));
}
