package com.github.cerealklla.settlemynts.guardhouse;

import net.minecraft.resources.Identifier;

/**
 * Design doc Section 14a's Guardhouse Plot Type -- shared constants for the "Inventory + Configure
 * Garrison UI" slice (2026-09-30), the second of three explicitly user-scoped passes (after
 * "Plot Type + structure only"). Guard spawning, gold upkeep, and patrol pathfinding are still real,
 * separate follow-up work.
 */
public final class GuardhouseConstants {

    private GuardhouseConstants() {
    }

    /**
     * Blueprynts registers the Guardhouse {@code BlueprintType} under this same id (see
     * {@code BluepryntsMod#commonSetup}) and bridges it one-for-one into this mod's own {@code
     * ZoneType} registry ({@code bridge.SettlemyntsZoneBridge}) -- this is the one place Settlemynts
     * needs to recognize the type specifically (everything else, like the generic {@code
     * npcOwnedOnly} rule, stays type-agnostic), since the garrison/food mechanics genuinely are
     * Guardhouse-specific business logic, not a generic Plot Type feature.
     */
    public static final Identifier GUARDHOUSE_ZONE_TYPE_ID = Identifier.fromNamespaceAndPath("blueprynts", "guardhouse");

    /** Max possible garrison size across every Tier -- {@link GarrisonSlot} arrays are always allocated at this size, with only the first {@link #capacityForTier} slots ever shown/settable, so a later Tier change (e.g. a rebuild at a higher Tier) never needs a resize/migration step. */
    public static final int MAX_GARRISON_SIZE = 8;

    /**
     * Design doc: "Garrison size cap by Tier: T1 = 2 guards up to T5 = 8 guards (exact intermediate
     * values not yet decided -- likely linear, not confirmed)." Placeholder linear formula matching
     * both stated endpoints exactly; retune here if a real curve is decided later -- flagged
     * explicitly, same "tune later" convention as {@code ZoneTierConstructionConfig}'s own cost
     * table. {@code tier <= 0} (unbound Construction Box, or no Blueprynts) returns 0 -- no garrison
     * slots can be configured until a real Tier is known.
     */
    public static int capacityForTier(int tier) {
        if (tier <= 0) {
            return 0;
        }
        int clamped = Math.max(1, Math.min(5, tier));
        return 2 + (clamped - 1) * 3 / 2;
    }

    /**
     * Design doc: "as Tier increases, the maximum-Tier gear a Guardhouse's guards are permitted to
     * wear improves (a cap, not a guarantee)." Placeholder 1:1 mapping (a Tier-N Guardhouse permits
     * up to Tier-N gear) -- explicitly flagged as tunable, same as {@link #capacityForTier}; no real
     * gear-Tier catalog exists anywhere in this suite yet, so a garrison slot's own {@code
     * maxGearTier} is validated against this cap, not against any real item registry.
     */
    public static int maxGearTierForTier(int tier) {
        return Math.max(1, Math.min(5, tier));
    }
}
