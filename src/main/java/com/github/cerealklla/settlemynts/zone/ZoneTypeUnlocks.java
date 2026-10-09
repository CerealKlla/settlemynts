package com.github.cerealklla.settlemynts.zone;

import java.util.Optional;

import com.github.cerealklla.settlemynts.bridge.LyfeMayorBridge;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;

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

    /**
     * The combined, real-world gate for whether {@code core}'s settlement could finalize a plot of
     * {@code zoneTypeId} right now -- both the Town Hall Tier gate above and Lyfe's Mayor-skill-level
     * gate together. Added 2026-10-09, explicit follow-up request: "I'd like to not even show them as
     * options in the plot type picker if they're unavailable" -- {@code
     * SettlemyntsMod#finalizePlot}'s own enforcement and {@code
     * zone.client.PlotStakeScreen}'s picker filtering (via {@code
     * zone.GhostPlotStakeEntity}'s own computation of {@code OpenPlotStakeScreenPayload
     * #allowedZoneTypeIds}) both call this one method, so the two can never disagree.
     */
    public static boolean isAllowed(ServerLevel level, GhostTownHallCoreEntity core, Identifier zoneTypeId) {
        if (zoneTypeId.equals(GhostTownHallCoreEntity.TOWN_HALL_ZONE_TYPE_ID)) {
            return true;
        }
        Optional<PlotRecord> townHall = core.townHallPlot();
        if (townHall.isEmpty()) {
            return false; // The settlement's Town Hall must be founded before any other Zone Type.
        }
        if (townHall.get().tier() < minTownHallTierForZoneType(zoneTypeId)) {
            return false;
        }
        if (LyfeMayorBridge.isLoaded()) {
            int minMayorLevel = LyfeMayorBridge.minMayorLevelForZoneType(zoneTypeId);
            int founderMayorLevel = LyfeMayorBridge.getMayorLevel(level.getServer(), core.getFounderId());
            return founderMayorLevel >= minMayorLevel;
        }
        return true;
    }
}
