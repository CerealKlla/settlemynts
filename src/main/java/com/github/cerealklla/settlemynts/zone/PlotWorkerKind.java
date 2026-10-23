package com.github.cerealklla.settlemynts.zone;

import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.guardhouse.GuardhouseConstants;

import net.minecraft.resources.Identifier;

/**
 * The single, authoritative answer to "what kind of plot-worker NPC (if any) should live at this
 * plot's sign right now" -- added 2026-10-16 alongside {@link PlotJob}, replacing four independent,
 * hand-duplicated zone-type checks that used to live one each inside {@code
 * resident.ResidentSpawnTicker}/{@code farm.FarmerSpawnTicker}/{@code
 * lumberyard.LumberjackSpawnTicker}/{@code mineshaft.MineshaftSpawnTicker}. Centralizing this was a
 * deliberate safety choice, not just tidiness: once {@link PlotJob} lets a player-owned plot's own
 * worker kind diverge from its real {@link PlotRecord#zoneTypeId()} (e.g. a Mineshaft-zoned plot
 * whose owner picked "Change Job" -> Farmer), four independently-duplicated gates would have a real
 * risk of ever disagreeing -- two different spawn tickers both deciding "yes, this plot is mine"
 * and filling two separate worker slots for the same plot. With every ticker instead asking this one
 * method the same question, exactly one answer can ever come back.
 *
 * <p>{@link PlotJob} is checked first and wins outright when set -- a player's explicit "Change Job"
 * choice always overrides whatever the plot's real Zone Type would otherwise imply. Guardhouse and
 * Town Hall are hard {@link Kind#NONE} regardless of {@link PlotJob} -- neither has a worker-NPC
 * concept at all (Guardhouse has its own separate garrison-guard mechanic; Town Hall is civic
 * infrastructure), and "Change Job" is never offered there in the first place (see {@code
 * SettlemyntsMod#requestChangeJob}'s own eligibility check), so a plot reaching this branch with a
 * non-NONE job set there would only ever be stale/pre-existing data, not a live request.
 */
public final class PlotWorkerKind {

    public enum Kind { NONE, RESIDENT, FARMER, LUMBERJACK, MINER }

    // Widened to public 2026-10-16 so FarmerWorkerEntity/LumberjackWorkerEntity can tell whether
    // they're on their own native Zone Type (full job: till/plant new ground too) or merely hired
    // via "Change Job" onto a different Zone Type like Private Residence (harvest/replant only --
    // see FarmerWorkGoal/LumberjackWorkGoal's own doc).
    public static final Identifier FARM_ZONE_TYPE_ID = Identifier.fromNamespaceAndPath("blueprynts", "farm");
    public static final Identifier LUMBERYARD_ZONE_TYPE_ID = Identifier.fromNamespaceAndPath("blueprynts", "lumberyard");
    // Widened to public 2026-10-23 so PlotConfigSignBlock can gate the new "Specialization" control
    // (Configure Plot, Mineshaft only) without duplicating this identifier.
    public static final Identifier MINESHAFT_ZONE_TYPE_ID = Identifier.fromNamespaceAndPath("blueprynts", "mineshaft");

    private PlotWorkerKind() {
    }

    public static Kind resolve(PlotRecord plot) {
        if (plot.zoneTypeId().equals(GuardhouseConstants.GUARDHOUSE_ZONE_TYPE_ID)
                || plot.zoneTypeId().equals(GhostTownHallCoreEntity.TOWN_HALL_ZONE_TYPE_ID)) {
            return Kind.NONE;
        }
        if (plot.job() == PlotJob.FARMER) {
            return Kind.FARMER;
        }
        if (plot.job() == PlotJob.LUMBERJACK) {
            return Kind.LUMBERJACK;
        }
        if (plot.zoneTypeId().equals(FARM_ZONE_TYPE_ID)) {
            return Kind.FARMER;
        }
        if (plot.zoneTypeId().equals(LUMBERYARD_ZONE_TYPE_ID)) {
            return Kind.LUMBERJACK;
        }
        if (plot.zoneTypeId().equals(MINESHAFT_ZONE_TYPE_ID)) {
            return Kind.MINER;
        }
        return Kind.RESIDENT;
    }
}
