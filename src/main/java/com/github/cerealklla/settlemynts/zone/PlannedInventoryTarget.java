package com.github.cerealklla.settlemynts.zone;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;

/**
 * One resource's "happy state" target stock level, set by a plot owner on {@code
 * client.PlannedInventoryScreen} (design doc Section 14a, added 2026-10-09, explicit user request) --
 * e.g. a Grocer targets 128 Apples, a Lumberjack targets 0 (it produces apples as a harvest byproduct
 * but has no use for them). Only a resource with an explicit entry here ever participates in {@code
 * zone.PlannedInventoryClearing}'s nightly inter-plot trading -- there is no implicit default target
 * for anything, and {@code targetCount == 0} is a deliberate, meaningful value (not a sentinel for
 * "remove this," unlike {@link SuppressedShopResource}'s 0-means-delete convention) -- removal happens
 * by simply not including a resource's row in the next {@code SetPlannedInventoryPayload} batch.
 *
 * <p>Persisted on {@link PlotRecord#plannedInventory()}. Mirrors {@link SuppressedShopResource}'s
 * shape exactly (same {@code resourceKey}/{@code isTag} resource identity convention used throughout
 * this package).
 *
 * <p>{@code derivedFromGuardKitStand} (added 2026-10-16, explicit user spec: "Each stand has its own
 * list # of preferred kits to keep on hand... these will reside within boxes inside the plot like
 * all other plots") marks an entry as owned by {@code guardhouse.GuardKitStandPreferredInventory}'s
 * own recompute pass rather than a player's manual edit on {@code client.PlannedInventoryScreen}.
 * That screen never shows or lets a player edit a {@code true} entry; its own "Save Changes" handler
 * (`SettlemyntsMod#setPlannedInventory`) preserves every existing {@code true} entry verbatim instead
 * of replacing the whole list, since a manual save only ever submits the entries it showed in the
 * first place. Defaults to {@code false} (including every pre-existing saved plot).
 */
public record PlannedInventoryTarget(Identifier resourceKey, boolean isTag, int targetCount, boolean derivedFromGuardKitStand) {

    public static final Codec<PlannedInventoryTarget> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("resource_key").forGetter(PlannedInventoryTarget::resourceKey),
            Codec.BOOL.fieldOf("is_tag").forGetter(PlannedInventoryTarget::isTag),
            Codec.INT.fieldOf("target_count").forGetter(PlannedInventoryTarget::targetCount),
            Codec.BOOL.optionalFieldOf("derived_from_guard_kit_stand", false).forGetter(PlannedInventoryTarget::derivedFromGuardKitStand)
    ).apply(i, PlannedInventoryTarget::new));

    /** Convenience for the common manual-entry case (every existing call site before this field existed). */
    public PlannedInventoryTarget(Identifier resourceKey, boolean isTag, int targetCount) {
        this(resourceKey, isTag, targetCount, false);
    }
}
