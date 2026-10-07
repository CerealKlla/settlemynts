package com.github.cerealklla.settlemynts.construction;

import java.util.List;

import com.github.cerealklla.blueprynts.blueprint.GenericResource;

import net.minecraft.resources.Identifier;

/**
 * The Zone Type + Tier construction cost/time table (design-document.md Section 14a, captured
 * 2026-09-29) -- Settlemynts owns this config, deliberately kept out of Blueprynts' own Blueprint
 * JSON, so it can be retuned freely without touching any saved Blueprint (per the user's own spec:
 * "The Required Materials and these time limits won't actually be in the blueprints, that way they
 * can be changed as testing/playing demands").
 *
 * <p><b>v1 placeholder formula, not per-Zone-Type yet</b> -- the exact per-type cost split (e.g. a
 * Lumberyard costing relatively more Wood than a Blacksmith) is real future tuning, not decided yet;
 * {@code zoneTypeId} is already part of this method's signature so that split can be added later
 * without another call-site change, but for now every Zone Type gets the same Tier-scaled formula.
 * Same "placeholder, retune by feel" convention already used by {@code TierSpec}/{@code SlabBudget}
 * in Blueprynts.
 */
public final class ZoneTierConstructionConfig {

    private static final int TICKS_PER_HOUR = 72000;
    private static final int TICKS_PER_MINUTE = 1200;

    // Real design value is "T1 = 1 hour, T2 = 2, etc" (the user's own words) -- temporarily
    // overridden to a flat 1 minute for testing (explicit request, 2026-09-29), since sitting
    // through real Tier-scaled hours isn't practical while iterating. Swap back to
    // `clampedTier * TICKS_PER_HOUR` once real-timescale testing is wanted again. Both
    // `minTimeTicks` (Blueprynts' construction-duration floor) and `maxTimeTicks` (NPC
    // auto-funding's target pace) are real, live-consumed values now -- this constant directly
    // controls how long both take in practice.
    private static final int TEST_MIN_TIME_TICKS = TICKS_PER_MINUTE;

    // NPC auto-funding's own target pace -- independent of TEST_MIN_TIME_TICKS above (previously
    // just `minTimeTicks * 2`), set to a flat 10 minutes 2026-09-30 per explicit request so an NPC
    // auto-funding test has enough real wall-clock time to actually watch deliveries arrive.
    private static final int TEST_MAX_TIME_TICKS = TICKS_PER_MINUTE * 10;

    // Temporary testing override (explicit request, 2026-09-29): every cost is free while other
    // parts of the funding loop get tested, so a Construction Box completes the instant it's bound
    // instead of needing real deposits first. Flipped back to `false` 2026-09-30 for NPC
    // auto-funding testing -- the user wants to watch real resources actually arrive in the box.
    private static final boolean TEST_FREE_CONSTRUCTION = false;

    private ZoneTierConstructionConfig() {
    }

    public static ConstructionRequirements get(Identifier zoneTypeId, int tier) {
        int clampedTier = Math.max(1, Math.min(5, tier));
        List<ResourceCost> costs = TEST_FREE_CONSTRUCTION
                ? List.of()
                : List.of(
                        new ResourceCost(GenericResource.WOOD, 50 * clampedTier),
                        new ResourceCost(GenericResource.STONE, 30 * clampedTier));
        int minTimeTicks = TEST_MIN_TIME_TICKS;
        int maxTimeTicks = TEST_MAX_TIME_TICKS;
        return new ConstructionRequirements(costs, minTimeTicks, maxTimeTicks);
    }
}
