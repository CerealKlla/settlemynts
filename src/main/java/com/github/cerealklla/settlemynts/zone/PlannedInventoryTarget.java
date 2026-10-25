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
 *
 * <p>{@code derivedFromConstructionBox} (added 2026-10-22, same shape/governance as {@code
 * derivedFromGuardKitStand} above) marks an entry as owned by Blueprynts' own Construction Box
 * funding rework ({@code bridge.BlueprintsConstructionBridge}/{@code
 * construction.ConstructionMaterialDemand}, pushed via {@code
 * api.Settlemynts#setConstructionMaterialTargets}) -- whatever real item the bound Blueprint is
 * still short of, after the box's own free pull from the plot's boxes. Also never shown/editable on
 * {@code client.PlannedInventoryScreen}, and also preserved verbatim by a manual save.
 *
 * <p>{@code keepPrivateCount} (added 2026-10-24, explicit user spec) -- a floor this resource's real
 * stock may never be sold below, independent of {@code targetCount} (which this screen now labels
 * "For Sale," not "Target Qty"). Per the user's own confirmation: "The Listed $ Amount means 'Sell
 * Items'. If there is no Keep#, then sell all the items of that type, otherwise ensure we keep the
 * Keep#. If there is a ForSale#, then attempt to restock up to that point after the sales occur such
 * that we have Keep# + ForSale#." So a plot's real desired stock level for a resource with a Shop
 * Config price listed is {@code keepPrivateCount + targetCount}; with no price listed, restocking
 * targets {@code keepPrivateCount} alone (ForSale is meaningless without something to sell it at) --
 * see {@code zone.PlannedInventoryClearing#desiredStockFor}, the one place this is actually computed.
 * Always {@code 0} for a derived entry (Guard Kit Stand/Construction Box demand, and the synthetic
 * Wishlist-material fallback {@code PlannedInventoryClearing#resolveEffectiveTarget} builds on the
 * fly) -- none of those represent a "keep some back, sell the rest" concept, only a flat amount
 * wanted. Defaults to {@code 0} (including every pre-existing saved plot).
 */
public record PlannedInventoryTarget(Identifier resourceKey, boolean isTag, int targetCount, int keepPrivateCount, boolean derivedFromGuardKitStand, boolean derivedFromConstructionBox) {

    public static final Codec<PlannedInventoryTarget> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("resource_key").forGetter(PlannedInventoryTarget::resourceKey),
            Codec.BOOL.fieldOf("is_tag").forGetter(PlannedInventoryTarget::isTag),
            Codec.INT.fieldOf("target_count").forGetter(PlannedInventoryTarget::targetCount),
            Codec.INT.optionalFieldOf("keep_private_count", 0).forGetter(PlannedInventoryTarget::keepPrivateCount),
            Codec.BOOL.optionalFieldOf("derived_from_guard_kit_stand", false).forGetter(PlannedInventoryTarget::derivedFromGuardKitStand),
            Codec.BOOL.optionalFieldOf("derived_from_construction_box", false).forGetter(PlannedInventoryTarget::derivedFromConstructionBox)
    ).apply(i, PlannedInventoryTarget::new));

    /** Convenience for the common manual-entry case (every existing call site before either derived flag existed) -- always {@code keepPrivateCount = 0}, see this class's own doc on why a derived/synthetic entry never has one. */
    public PlannedInventoryTarget(Identifier resourceKey, boolean isTag, int targetCount) {
        this(resourceKey, isTag, targetCount, 0, false, false);
    }

    /** Convenience for the existing Guard Kit Stand call site (unchanged signature). */
    public PlannedInventoryTarget(Identifier resourceKey, boolean isTag, int targetCount, boolean derivedFromGuardKitStand) {
        this(resourceKey, isTag, targetCount, 0, derivedFromGuardKitStand, false);
    }

    /** Convenience for the existing Construction Box demand call site (unchanged signature) -- always {@code keepPrivateCount = 0}, same reasoning as the 3-arg convenience above. */
    public PlannedInventoryTarget(Identifier resourceKey, boolean isTag, int targetCount, boolean derivedFromGuardKitStand, boolean derivedFromConstructionBox) {
        this(resourceKey, isTag, targetCount, 0, derivedFromGuardKitStand, derivedFromConstructionBox);
    }

    /** {@code true} if this entry is owned by any automatic system, not a player's own manual edit -- the one check {@code client.PlannedInventoryScreen}'s own row-filtering and a manual save's own preserve-logic actually need. */
    public boolean isDerived() {
        return derivedFromGuardKitStand || derivedFromConstructionBox;
    }
}
