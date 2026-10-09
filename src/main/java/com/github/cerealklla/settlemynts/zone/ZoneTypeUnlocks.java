package com.github.cerealklla.settlemynts.zone;

import net.minecraft.resources.Identifier;

/**
 * Minimum Town Hall Tier a Zone Type requires to exist at all in a settlement (added 2026-10-09,
 * explicit spec: "the town hall's tier itself allows a mayor to place those zone types. so a Tier 1
 * town can't have a Guardhouse at all, whether the Mayor has the skill to manage one or not") --
 * deliberately separate from (and in addition to) Lyfe's own Mayor-skill-level gate (see {@code
 * bridge.LyfeMayorBridge#minMayorLevelForZoneType}), which this class has no dependency on.
 *
 * <p><b>Placeholder schedule, this class's own judgment call</b> (explicit user instruction: "you
 * decide what seems reasonable and then we'll tweak") -- matched to {@code
 * mayor.MayorConstants#minMayorLevelForZoneType}'s own rough ordering (Lyfe side) so the two gates
 * agree on which Zone Types are "basic" vs. "advanced," without being literally identical numbers
 * (a settlement's infrastructure Tier and an individual Mayor's own skill level are different axes).
 */
public final class ZoneTypeUnlocks {

    private ZoneTypeUnlocks() {
    }

    public static int minTownHallTierForZoneType(Identifier zoneTypeId) {
        return switch (zoneTypeId.getPath()) {
            case "town_hall", "farm", "lumberyard", "private_residence", "blacksmith" -> 1;
            case "guardhouse", "grocer", "stonemason", "building_supplier" -> 2;
            case "armorer", "restaurant", "tavern" -> 3;
            case "traveling_merchant_stall" -> 4;
            default -> 1;
        };
    }
}
