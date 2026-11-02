package com.github.cerealklla.settlemynts.founding;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.github.cerealklla.cartographyr.api.Cartography;
import com.github.cerealklla.cartographyr.geo.EntityId;
import com.github.cerealklla.cartographyr.geo.GeographicEntity;
import com.github.cerealklla.cartographyr.geo.Geometry;
import com.github.cerealklla.settlemynts.zone.PlotRecord;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Resolves the real chunk range a settlement's own finalized plots cover -- one representative
 * {@link BlockPos} per chunk, unioning every plot's real Cartographyr polygon (same lookup
 * {@code SettlemyntsMod}'s own "Show Plot Perimeters" wall regeneration already uses:
 * {@code Cartography.getEntity(level, new EntityId(plot.cartographyrPlotEntityId()))}, reading its
 * {@code Geometry.Polygon} via {@code Geometry.Polygon.outerRing}) -- a deliberately bounding-box
 * approximation, not an exact per-chunk polygon test: may force a handful of extra corner chunks
 * outside a non-square settlement's real shape, which is bounded and cheap at this scale, not a
 * correctness problem (see {@code zone.SettlementProximityTicker}'s own doc).
 *
 * <p>Requires the owning {@code GhostTownHallCoreEntity}'s own chunk to already be loaded -- its
 * plot list lives on the live entity itself, not a chunk-independent index, same two-step
 * constraint {@code zone.ShopMidnightRestockTicker#wakeAndPipelineSettlement} already has to work
 * around for the exact same reason.
 */
public final class SettlementBoundingBox {

    private SettlementBoundingBox() {
    }

    public static List<BlockPos> resolveChunkRepresentatives(ServerLevel level, GhostTownHallCoreEntity core) {
        int minChunkX = Integer.MAX_VALUE;
        int maxChunkX = Integer.MIN_VALUE;
        int minChunkZ = Integer.MAX_VALUE;
        int maxChunkZ = Integer.MIN_VALUE;

        BlockPos corePos = core.blockPosition();
        int coreChunkX = Math.floorDiv(corePos.getX(), 16);
        int coreChunkZ = Math.floorDiv(corePos.getZ(), 16);
        minChunkX = Math.min(minChunkX, coreChunkX);
        maxChunkX = Math.max(maxChunkX, coreChunkX);
        minChunkZ = Math.min(minChunkZ, coreChunkZ);
        maxChunkZ = Math.max(maxChunkZ, coreChunkZ);

        for (PlotRecord plot : core.getPlots()) {
            Optional<GeographicEntity> plotEntity = Cartography.getEntity(level, new EntityId(plot.cartographyrPlotEntityId()));
            if (plotEntity.isEmpty() || !(plotEntity.get().geometry() instanceof Geometry.Polygon polygon)) {
                continue;
            }
            for (Geometry.Polygon.Vertex vertex : Geometry.Polygon.outerRing(polygon)) {
                int chunkX = Math.floorDiv(vertex.x(), 16);
                int chunkZ = Math.floorDiv(vertex.z(), 16);
                minChunkX = Math.min(minChunkX, chunkX);
                maxChunkX = Math.max(maxChunkX, chunkX);
                minChunkZ = Math.min(minChunkZ, chunkZ);
                maxChunkZ = Math.max(maxChunkZ, chunkZ);
            }
        }

        List<BlockPos> positions = new ArrayList<>();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                positions.add(new BlockPos(chunkX * 16, 64, chunkZ * 16));
            }
        }
        return positions;
    }
}
