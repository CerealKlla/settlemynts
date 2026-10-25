package com.github.cerealklla.settlemynts.plotsign;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;

/**
 * One row's edit on {@code client.plotsign.client.ShopTab}'s Planned Inventory "Save Changes" --
 * part of {@link SetPlannedInventoryPayload}'s batch. {@code managed == false} means "this resource
 * isn't tracked by Planned Inventory at all" (both the For Sale and Keep Private EditBoxes were left
 * blank) -- no {@code zone.PlannedInventoryTarget} is kept for it. {@code managed == true} sets/
 * creates a target at exactly {@code targetCount}/{@code keepPrivateCount} (either of which may
 * legitimately be {@code 0} -- a deliberate "none" value, not a removal signal, see {@code
 * zone.PlannedInventoryTarget}'s own class doc for why this differs from {@link ShopListingUpdate}'s
 * 0-means-delete convention). {@code keepPrivateCount} added 2026-10-24.
 */
public record PlannedInventoryUpdate(Identifier resourceKey, boolean isTag, boolean managed, int targetCount, int keepPrivateCount) {

    public static final Codec<PlannedInventoryUpdate> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("resource_key").forGetter(PlannedInventoryUpdate::resourceKey),
            Codec.BOOL.fieldOf("is_tag").forGetter(PlannedInventoryUpdate::isTag),
            Codec.BOOL.fieldOf("managed").forGetter(PlannedInventoryUpdate::managed),
            Codec.INT.fieldOf("target_count").forGetter(PlannedInventoryUpdate::targetCount),
            Codec.INT.optionalFieldOf("keep_private_count", 0).forGetter(PlannedInventoryUpdate::keepPrivateCount)
    ).apply(i, PlannedInventoryUpdate::new));
}
