package com.github.cerealklla.settlemynts.bills;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.github.cerealklla.cartographyr.api.Cartography;
import com.github.cerealklla.cartographyr.geo.EntityId;
import com.github.cerealklla.cartographyr.geo.GeographicEntity;
import com.github.cerealklla.settlemynts.bridge.YconomicsBillBridge;

import net.minecraft.server.level.ServerLevel;

/**
 * Finds every Box-Identity-tracked container (Cartographyr's feature, not Blueprynts' unrelated
 * Construction Box) currently sitting inside a plot's real polygon -- a one-time full rediscovery,
 * used only for seeding a brand-new bill's {@code destinationBoxes} at Finalize time (see
 * {@code SettlemyntsMod#finalizePlot}) against boxes that already existed before the bill did.
 * Every ongoing update after that point is incremental (see {@link PlotBillBoxListener}), never a
 * rescan like this one -- this is deliberately not reused for that (2026-10-22, see decisions.md,
 * supersedes the old {@code PlotRentTicker} which called this every 20 ticks for every settlement).
 */
public final class PlotBoxDiscovery {

    private PlotBoxDiscovery() {
    }

    public static List<YconomicsBillBridge.BoxRef> findBoxes(ServerLevel level, long cartographyrEntityId) {
        Optional<GeographicEntity> entity = Cartography.getEntity(level, new EntityId(cartographyrEntityId));
        if (entity.isEmpty()) {
            return List.of();
        }
        Set<UUID> boxIds = Cartography.getBoxesAt(level, entity.get().geometry());
        List<YconomicsBillBridge.BoxRef> refs = new ArrayList<>(boxIds.size());
        for (UUID boxId : boxIds) {
            Cartography.getBoxLocation(level, boxId).ifPresent(pos -> refs.add(new YconomicsBillBridge.BoxRef(boxId, pos)));
        }
        return refs;
    }
}
