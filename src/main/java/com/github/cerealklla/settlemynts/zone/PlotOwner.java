package com.github.cerealklla.settlemynts.zone;

import java.util.List;
import java.util.UUID;

/**
 * Whatever persists a group of {@link PlotRecord}s and can be updated as they change. Extracted
 * 2026-10-08 so {@link ShopSeeding} (and anything else that only ever needs to read/add/update a
 * plot list) can work the same way whether the plots belong to a player-founded settlement ({@code
 * founding.GhostTownHallCoreEntity}, which already looked exactly like this) or a naturally-spawned
 * village with no Town Hall Core at all ({@link NaturalSettlementPlotOwner}).
 */
public interface PlotOwner {
    UUID getUUID();

    List<PlotRecord> getPlots();

    void addPlot(PlotRecord plot);

    void updatePlot(PlotRecord updated);
}
