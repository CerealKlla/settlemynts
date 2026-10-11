package com.github.cerealklla.settlemynts.roadway;

import java.util.List;

import com.github.cerealklla.settlemynts.zone.PlotRecord;

/**
 * "A Settlement Tier is simply the average of all the plot tiers within it" -- a brand-new concept,
 * added 2026-10-11 specifically so a Highway's paved quality can reflect both endpoint settlements
 * ("the actual quality of the road will be determined by the average Tier of the two endpoint
 * settlements"). Deliberately takes a plain {@code List<PlotRecord>}, same "operate on the list, not
 * on 'today's settlement'" shape {@code zone.PlannedInventoryClearing} already uses -- works
 * identically for a player-founded settlement ({@code GhostTownHallCoreEntity#getPlots()}) or a
 * natural one ({@code zone.NaturalSettlementPlotStore#getPlots}), since both already expose the same
 * {@code List<PlotRecord>} shape.
 *
 * <p>Natural (NPC-owned) settlements start at a real disadvantage here -- untouched plots default to
 * {@link PlotRecord#tier()} {@code 1}. This is intentional, not a bug to fix: the user's own
 * follow-up design note is that NPC-generated quests can now send a player to fetch upgrade
 * resources for a natural settlement's own plots, which is a separate, not-yet-built quest style --
 * this resolver just needs to produce the right number once that exists.
 */
public final class SettlementTierResolver {

    private SettlementTierResolver() {
    }

    /** Rounded average of every plot's {@link PlotRecord#tier()} -- {@code 1} (the lowest real tier) if the settlement has no plots at all. */
    public static int averageTier(List<PlotRecord> plots) {
        if (plots.isEmpty()) {
            return 1;
        }
        double sum = 0;
        for (PlotRecord plot : plots) {
            sum += plot.tier();
        }
        return Math.max(1, Math.round((float) (sum / plots.size())));
    }

    /** The tier a Highway edge between two settlements should pave at -- the rounded average of each side's own {@link #averageTier}. Either side may be an empty list (a settlement this resolver can't see into yet), which just falls back to tier 1 for that side alone, not the whole result. */
    public static int averageOfTwoSettlements(List<PlotRecord> plotsA, List<PlotRecord> plotsB) {
        return Math.max(1, Math.round((averageTier(plotsA) + averageTier(plotsB)) / 2.0f));
    }
}
