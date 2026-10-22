package com.github.cerealklla.settlemynts.construction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.zone.PlannedInventoryTarget;
import com.github.cerealklla.settlemynts.zone.PlotRecord;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;

/**
 * Wholesale-recomputes a plot's {@code derivedFromConstructionBox} Planned Inventory targets from
 * whatever Blueprynts reports is still missing -- the Settlemynts-side half of the Construction Box
 * funding rework (2026-10-22, explicit user spec: "we can treat the midnight purchase as
 * automatically added/removed Planned Inventory and leverage the existing pipeline entirely for the
 * purchasing"). Called from {@code bridge.BlueprintsConstructionBridge} whenever Blueprynts' own
 * {@code ConstructionBoxBlockEntity} pushes an updated shortfall (a deposit, a bind, or a plot-stock
 * pull) -- same "derived entry, recomputed wholesale, never touched by a player's own manual edit"
 * shape as {@code guardhouse.GuardKitStandPreferredInventory}, which this class mirrors directly.
 *
 * <p>{@code zone.PlannedInventoryClearing}'s existing nightly inter-plot trading then funds these
 * entries with zero changes needed there -- this class only ever writes the target list, never buys
 * anything itself.
 */
public final class ConstructionMaterialDemand {

    private ConstructionMaterialDemand() {
    }

    public static void recalculate(ServerLevel level, UUID settlementCoreId, UUID plotId, Map<Identifier, Integer> missing) {
        if (settlementCoreId == null || plotId == null || !(level.getEntity(settlementCoreId) instanceof GhostTownHallCoreEntity core)) {
            return;
        }
        PlotRecord plot = core.getPlots().stream().filter(p -> p.plotId().equals(plotId)).findFirst().orElse(null);
        if (plot == null) {
            return;
        }

        List<PlannedInventoryTarget> updated = new ArrayList<>();
        for (PlannedInventoryTarget existing : plot.plannedInventory()) {
            if (!existing.derivedFromConstructionBox()) {
                updated.add(existing); // Never touch a player's own manually-set target, or Guard Kit Stand's own.
            }
        }
        missing.forEach((itemId, amount) -> updated.add(new PlannedInventoryTarget(itemId, false, amount, false, true)));
        core.updatePlot(plot.withPlannedInventory(updated));
    }
}
