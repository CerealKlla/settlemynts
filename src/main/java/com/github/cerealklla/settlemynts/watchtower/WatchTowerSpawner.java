package com.github.cerealklla.settlemynts.watchtower;

import java.util.Optional;
import java.util.UUID;

import com.github.cerealklla.settlemynts.registration.ModBlocks;
import com.github.cerealklla.settlemynts.zone.PlotSitePlacement;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Spawns a Watch-Tower-typed plot's own Beacon at Finalize time -- mirrors
 * {@code guardhouse.GuardhouseSpawner} exactly, including its idempotency guard (a double-submitted
 * Finalize click/network retry must not silently orphan an already-placed Beacon's own fuel/state).
 * Lands two cells laterally beside the same site the Plot Config Sign anchors to.
 */
public final class WatchTowerSpawner {

    private WatchTowerSpawner() {
    }

    public static void spawn(ServerLevel level, UUID settlementCoreId, UUID plotId, PlotSitePlacement.Site site) {
        WatchTowerIndex index = WatchTowerIndex.get(level.getServer());
        Optional<GlobalPos> existing = index.get(plotId);
        if (existing.isPresent() && existing.get().dimension().equals(level.dimension())
                && level.getBlockEntity(existing.get().pos()) instanceof WatchTowerBeaconBlockEntity) {
            return; // Already has a real Beacon -- see this class's own doc for why this matters.
        }

        Direction lateral = site.towardBlue().getClockWise();
        BlockPos beaconPos = site.pos().relative(lateral, 2);

        level.setBlock(beaconPos, ModBlocks.WATCH_TOWER_BEACON.get().defaultBlockState(), 3);
        if (level.getBlockEntity(beaconPos) instanceof WatchTowerBeaconBlockEntity beacon) {
            beacon.setIdentity(settlementCoreId, plotId);
        }
        index.put(plotId, GlobalPos.of(level.dimension(), beaconPos));
    }
}
