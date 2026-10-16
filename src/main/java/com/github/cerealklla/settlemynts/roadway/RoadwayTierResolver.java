package com.github.cerealklla.settlemynts.roadway;

import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.zone.PlotRecord;

/**
 * Resolves "what Tier should this settlement's roads currently look like" (added 2026-10-09,
 * explicit request: "a visual progression which is directly tied to a settlement's town hall
 * tier... no town hall/T1 Town Hall should use a dirt road... when the Town Hall is upgraded all
 * the roads will upgrade with it"). Reads the Town Hall plot's own unlocked Tier cap ({@link
 * PlotRecord#tier}) directly -- the same value "Upgrade Plot" raises and {@link RoadwayRetier}
 * already uses to instantly repaint every existing road the moment that upgrade succeeds (see
 * {@code SettlemyntsMod}'s own "Upgrade Plot" handler, which calls both off the exact same {@code
 * newTier}). Defaults to Tier 1 (dirt) whenever there's no Town Hall plot yet, same "no Town Hall"
 * case the user explicitly called out.
 *
 * <p><b>Fixed 2026-10-16</b> -- real live report: a Tier 5 settlement's roads still paved at Tier
 * 3 when manually rebuilt (new stakes placed/reconnected). Root cause: this method used to re-derive
 * the tier from the Town Hall's Construction Box's own *bound Blueprint* Tier instead ({@code
 * PlotRecord#tier} and a Construction Box's bound Blueprint Tier are two genuinely independent
 * values by design -- see {@code PlotRecord#tier}'s own class doc, "you can upgrade the tier to
 * tier 2 and still have a tier 1 building"). That made this method disagree with {@link
 * RoadwayRetier}, which already (and correctly) uses the plot's own Tier directly -- existing roads
 * got auto-repainted to the new Tier the instant "Upgrade Plot" succeeded, but any road paved fresh
 * afterward fell back to whatever (lower) Blueprint Tier happened to still be physically built/bound
 * on the box. Switched to read {@code PlotRecord#tier} directly, matching {@link RoadwayRetier} so
 * the two paths can no longer disagree.
 */
public final class RoadwayTierResolver {

    private RoadwayTierResolver() {
    }

    public static int currentTier(GhostTownHallCoreEntity core) {
        return core.getPlots().stream()
                .filter(p -> GhostTownHallCoreEntity.TOWN_HALL_ZONE_TYPE_ID.equals(p.zoneTypeId()))
                .findFirst()
                .map(PlotRecord::tier)
                .orElse(1);
    }
}
