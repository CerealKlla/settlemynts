package com.github.cerealklla.settlemynts.watchtower;

import java.util.UUID;

import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Explicit user design, 2026-10-22: "the dry check can happen when an inventory changes... is there
 * more fuel? is the light on? transfer and resume burning" -- clarified to mean any storage on the
 * whole plot, not just the Beacon's own fuel slot (which already reacts to a direct deposit itself,
 * see {@code WatchTowerBeaconBlockEntity}'s own fuel container). Since Watch Tower is NPC-owned
 * only, the one real way Coal/Charcoal ever lands in its plot's boxes is {@code
 * zone.PlannedInventoryClearing}'s midnight trading engine -- call {@link #notifyPlotBoxesChanged}
 * right after any successful delivery there. Deliberately NOT hooked for a player manually dropping
 * coal into some other box on the plot (not the fuel slot) -- an accepted, narrow gap, not a poll.
 */
public final class WatchTowerRefuelNotifier {

    private WatchTowerRefuelNotifier() {
    }

    public static void notifyPlotBoxesChanged(ServerLevel level, UUID plotId) {
        java.util.Optional<GlobalPos> beaconPos = WatchTowerIndex.get(level.getServer()).get(plotId);
        if (beaconPos.isEmpty()) {
            return;
        }
        GlobalPos pos = beaconPos.get();
        ServerLevel beaconLevel = level.getServer().getLevel(pos.dimension());
        if (beaconLevel != null && beaconLevel.getBlockEntity(pos.pos()) instanceof WatchTowerBeaconBlockEntity beacon) {
            beacon.onPlotBoxesChanged(beaconLevel);
        }
    }
}
