package com.github.cerealklla.settlemynts.zone;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;

import net.minecraft.server.level.ServerLevel;

/**
 * "Mayor/Town Planner/Plot Owner" -- the permission gate the design doc calls for on the Plot Config
 * Sign's "Configure Plot"/"Manage Shop" buttons (design doc Section 14a, added 2026-09-30 alongside
 * {@link PlotRecord#owner}), and the same rule Protectyons' permitted-player set (see
 * {@code bridge.ProtectyonsPlotBridge}) is built from.
 *
 * <p><b>Reworked 2026-10-05</b> (explicit user request) -- a privately-owned plot (non-empty {@link
 * PlotRecord#owner}) is no longer freely editable by every Town Planner. The three rules are now:
 * <ol>
 *   <li>The Mayor (the settlement's founder) can always manage any plot, owned or not.</li>
 *   <li>Any other Town Planner can only manage a plot that is NOT privately owned (owner blank,
 *       i.e. NPC-owned).</li>
 *   <li>A plot's own assigned Owner can always manage their own plot.</li>
 * </ol>
 * Previously every Town Planner (the founder included, since the founder is auto-added to the
 * Town Planner set) could manage every plot regardless of ownership -- that was too broad once
 * plots started being privately owned.
 */
public final class PlotPermissions {

    private PlotPermissions() {
    }

    public static boolean canManage(PlotRecord plot, GhostTownHallCoreEntity core, UUID playerId) {
        if (core.isFounder(playerId)) {
            return true;
        }
        if (plot.owner().map(playerId::equals).orElse(false)) {
            return true;
        }
        return plot.owner().isEmpty() && core.isTownPlanner(playerId);
    }

    /**
     * The real set of players Protectyons should treat as permitted to break/place blocks within
     * this plot's polygon -- the same rule as {@link #canManage} above, computed once so Protectyons
     * registration/re-sync (see {@code SettlemyntsMod}) and the UI gate can never drift apart again.
     */
    public static Set<UUID> computeProtectionPermittedPlayers(PlotRecord plot, GhostTownHallCoreEntity core) {
        Set<UUID> permitted = new HashSet<>();
        UUID founderId = core.getFounderId();
        if (founderId != null) {
            permitted.add(founderId);
        }
        if (plot.owner().isEmpty()) {
            permitted.addAll(core.getTownPlanners());
        }
        plot.owner().ifPresent(permitted::add);
        return permitted;
    }

    /**
     * Applies a new Owner to {@code plot} and re-syncs Protectyons' permitted-player set from it --
     * the one real mutation path the admin {@code debug.DebugCommands#setPlotOwner} command and the
     * in-game "Change Owner" control ({@code plotsign.SetPlotOwnerFromSignPayload}, 2026-11-17) both
     * share, so the two can never drift apart. Returns the updated record (the caller already has
     * {@code core}, which is mutated in place by {@link GhostTownHallCoreEntity#updatePlot}).
     */
    public static PlotRecord applyOwnerChange(ServerLevel level, GhostTownHallCoreEntity core, PlotRecord plot, Optional<UUID> newOwner) {
        PlotRecord updated = plot.withOwner(newOwner);
        core.updatePlot(updated);
        if (net.neoforged.fml.ModList.get().isLoaded("protectyons")) {
            Set<UUID> permitted = computeProtectionPermittedPlayers(updated, core);
            com.github.cerealklla.settlemynts.api.Settlemynts.resolvePolygonDirect(level, updated).ifPresent(polygon ->
                    com.github.cerealklla.settlemynts.bridge.ProtectyonsPlotBridge.registerPlot(level, updated.plotId(), polygon, permitted));
        }
        return updated;
    }
}
