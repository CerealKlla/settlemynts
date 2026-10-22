package com.github.cerealklla.settlemynts.watchtower;

import net.minecraft.resources.Identifier;

/**
 * Watch Tower Plot Type (explicit user spec, 2026-10-22): a Beacon structure that casts passive
 * real illumination (via genuine {@code Blocks.LIGHT} placement, not a fake/overlay effect -- see
 * {@link WatchTowerLightController}) around itself, scaled by the plot's bound Blueprint's own
 * Tier (not the separately-upgradeable Plot Tier). See the approved plan at
 * {@code idempotent-floating-flamingo.md} for the full design.
 */
public final class WatchTowerConstants {

    private WatchTowerConstants() {
    }

    /**
     * Blueprynts registers the Watch Tower {@code BlueprintType} under this same id (see
     * {@code BluepryntsMod#commonSetup}) and bridges it one-for-one into this mod's own
     * {@code ZoneType} registry -- same shape as {@code guardhouse.GuardhouseConstants
     * #GUARDHOUSE_ZONE_TYPE_ID}.
     */
    public static final Identifier WATCH_TOWER_ZONE_TYPE_ID = Identifier.fromNamespaceAndPath("blueprynts", "watch_tower");

    /** Real-world minutes between fuel/radius re-evaluations -- matches the user's own "per minute" spec exactly. */
    public static final int MINUTE_TICKS = 1200;

    /** Grid spacing for placed light points, derived (not guessed) from vanilla light falloff: level
     * 15 light drops 1/block, so the worst-case gap (the diagonal midpoint between 4 grid points)
     * must stay {@code >= 8} for {@code guardhouse.GuardSightGoal}'s own "lit" threshold to hold
     * everywhere -- {@code 15 - sqrt(2)*(9/2) ~= 8.6 >= 8}, spacing much past 9-10 fails this. */
    public static final int LIGHT_GRID_SPACING_BLOCKS = 9;

    /**
     * Base illumination radius by built-structure Tier (design spec: "T1 25 cube radius to T5 50
     * cube radius"), linearly interpolated across the 3 middle tiers -- flagged tunable like every
     * other such table in this suite (e.g. {@code guardhouse.GuardhouseConstants#capacityForTier}).
     * {@code tier <= 0} (nothing built yet) returns 0 -- no light at all until a Blueprint is bound.
     */
    public static int baseRadiusForTier(int tier) {
        if (tier <= 0) {
            return 0;
        }
        int clamped = Math.max(1, Math.min(5, tier));
        return 25 + (clamped - 1) * 25 / 4;
    }

    /**
     * Coal/Charcoal consumed per real minute to double the radius (design spec: "10 + (Tier# * 10)
     * coal per minute"), scaled by the same built-structure Tier {@link #baseRadiusForTier} uses.
     */
    public static int fuelCostForTier(int tier) {
        return 10 + tier * 10;
    }
}
