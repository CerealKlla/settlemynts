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
 * Construction Box) currently sitting inside a plot's real polygon -- used by {@link
 * PlotRentTicker} for both a plot's own rent source and the settlement's shared Town Hall
 * destination, since both are "whatever box(es) a player happens to have placed in this area"
 * (explicit user decision, 2026-10-05: "it's up to players to place these boxes").
 */
final class PlotBoxDiscovery {

    private PlotBoxDiscovery() {
    }

    static List<YconomicsBillBridge.BoxRef> findBoxes(ServerLevel level, long cartographyrEntityId) {
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
