package com.github.cerealklla.settlemynts.construction;

import java.util.List;

import com.github.cerealklla.blueprynts.blueprint.GenericResource;

/**
 * The resource cost table for raising a plot's own unlocked construction-Tier cap (added
 * 2026-10-09, explicit user correction/request) -- a genuinely separate cost from {@link
 * ZoneTierConstructionConfig} (which prices *building* at a given Tier on the Construction Box).
 * "You can upgrade the tier to tier 2 and still have a tier 1 building, as that's a separate cost to
 * upgrade" -- raising the cap and rebuilding the physical structure are two independent actions with
 * two independent costs; see {@code zone.PlotRecord#tier}'s own class doc for the full split.
 *
 * <p><b>Placeholder values, same convention as every other cost/XP table in this suite</b> -- base
 * amounts are for the very first upgrade (Tier 1 -> 2); each subsequent Tier scales linearly
 * (multiplier = {@code nextTier - 1}), flagged for retuning after the first live playtest.
 */
public final class PlotTierUpgradeCost {

    private static final int BASE_WOOD_AMOUNT = 100;
    private static final int BASE_STONE_AMOUNT = 75;

    private PlotTierUpgradeCost() {
    }

    public static List<ResourceCost> costFor(int nextTier) {
        int multiplier = Math.max(1, nextTier - 1);
        return List.of(
                new ResourceCost(GenericResource.WOOD, BASE_WOOD_AMOUNT * multiplier),
                new ResourceCost(GenericResource.STONE, BASE_STONE_AMOUNT * multiplier)
        );
    }
}
