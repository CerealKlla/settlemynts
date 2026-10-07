package com.github.cerealklla.settlemynts.plotsign;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.github.cerealklla.blueprynts.api.PlotArea;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Holds a Plot Config Sign's identity (and, unlike the sign's own persisted state, its plot's real
 * {@link PlotArea} bounds) between "Relocate Plot Sign" removing the old block and a {@code
 * PlotConfigSignRelocatorItem} placing a fresh one somewhere else -- mirrors Blueprynts' {@code
 * construction.PendingSupplyBoxRelocation} exactly, including its own reasoning for staying plain
 * server-side, in-memory, never persisted (a server restart mid-reposition just means the granted
 * item silently fails to place, same accepted low-stakes edge case that class's own doc describes).
 * {@code plotArea} is resolved once, at "Relocate Plot Sign" time (via {@code
 * Blueprynts.getConstructionBoxStatus}), rather than re-resolved at placement time, so {@code
 * PlotConfigSignRelocatorItem#getPlacementState} needs no level access of its own to validate a
 * target position -- same shape as the box's own pending-relocation snapshot.
 */
public final class PendingPlotConfigSignRelocation {

    private record Snapshot(UUID settlementCoreId, UUID plotId, Direction facing, PlotArea plotArea, BlockPos originalPos) {
    }

    private static final Map<UUID, Snapshot> PENDING = new ConcurrentHashMap<>();

    private PendingPlotConfigSignRelocation() {
    }

    public static void store(UUID plotId, UUID settlementCoreId, Direction facing, PlotArea plotArea, BlockPos originalPos) {
        PENDING.put(plotId, new Snapshot(settlementCoreId, plotId, facing, plotArea, originalPos));
    }

    /** Read-only peek at a pending relocation's preserved facing -- doesn't consume the entry. {@code null} if nothing pending for this plot (an "already used" or never-relocated locator). */
    public static Direction peekFacing(UUID plotId) {
        Snapshot snapshot = PENDING.get(plotId);
        return snapshot == null ? null : snapshot.facing();
    }

    /** Read-only peek at the plot's real bounds -- doesn't consume the entry. {@code null} if nothing pending, or the plot has no known bounds (e.g. its Construction Box's chunk isn't loaded). */
    public static PlotArea peekPlotArea(UUID plotId) {
        Snapshot snapshot = PENDING.get(plotId);
        return snapshot == null ? null : snapshot.plotArea();
    }

    /** Read-only peek at the position this sign was removed from -- used to auto-restore it there if the Locator is lost (walked away, died, logged out) instead of ever placed. Doesn't consume the entry. {@code null} if nothing pending. */
    public static BlockPos peekOriginalPos(UUID plotId) {
        Snapshot snapshot = PENDING.get(plotId);
        return snapshot == null ? null : snapshot.originalPos();
    }

    /** @return the preserved settlement core id to restore onto the freshly-placed sign, or {@code null} if nothing is pending for this plot. */
    public static UUID applyTo(PlotConfigSignBlockEntity sign, UUID plotId) {
        Snapshot snapshot = PENDING.remove(plotId);
        if (snapshot == null) {
            return null;
        }
        sign.setIdentity(snapshot.settlementCoreId(), snapshot.plotId());
        return snapshot.settlementCoreId();
    }
}
