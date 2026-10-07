package com.github.cerealklla.settlemynts.zone;

import com.github.cerealklla.cartographyr.geo.Geometry;
import com.github.cerealklla.cartographyr.geo.PlotValidity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Finds the buildable site a plot's Building Supply Box (and, alongside it, its Plot Config Sign)
 * anchor to -- among every GREEN cell orthogonally adjacent to a BLUE cell ("just outside the
 * buildable area"), the one closest to the plot's own Road Access Flag. Deliberately Blueprynts-free
 * (only Cartographyr's own {@code PlotValidity}/{@code Geometry} types, both already a required
 * dependency of this mod) -- extracted 2026-09-30 from {@code bridge.BlueprintsConstructionBridge},
 * which used to compute this same thing internally, so a Plot Config Sign can exist and be sensibly
 * placed even on a Blueprynts-less server where no Building Supply Box (and so no structure at all)
 * will ever exist for this plot. User's own framing: "the Plot Sign should spawn at the same time as
 * the Construction Box spawns, but next to the box, not on it... if a server doesn't have Blueprynts
 * running there isn't an inherent dependency. In that scenario the Construction Box would never even
 * spawn, but we'd still want the Plot Sign to exist."
 */
public final class PlotSitePlacement {

    private PlotSitePlacement() {
    }

    /**
     * @param towardBlue the direction from {@code pos} into the plot's own buildable (BLUE) area --
     *                    a Building Supply Box (or a fallback-placed Plot Config Sign) faces {@code
     *                    towardBlue.getOpposite()}, matching the existing "furnace convention" this
     *                    whole suite already uses for anchor-facing.
     * @param grid/minX/minZ/maxX/maxZ the same classified grid the site was found in, kept here so a
     *                    caller that also needs a {@code BuildableArea}/{@code PlotArea} reduction of
     *                    it (Blueprynts-specific types) doesn't have to re-run {@code
     *                    PlotValidity.classify} a second time.
     */
    public record Site(BlockPos pos, Direction towardBlue, PlotValidity.Grid grid, int minX, int minZ, int maxX, int maxZ) {
    }

    /** {@code null} only if the polygon somehow has no valid buildable area at all -- callers must have already confirmed {@code PlotValidity#hasValidArea} on this same polygon (Finalize's own hard gate), so that shouldn't happen in practice. */
    public static Site find(ServerLevel level, Geometry.Polygon plotPolygon, BlockPos roadFlagPos) {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Geometry.Polygon.Vertex v : plotPolygon.vertices()) {
            minX = Math.min(minX, v.x());
            minZ = Math.min(minZ, v.z());
            maxX = Math.max(maxX, v.x());
            maxZ = Math.max(maxZ, v.z());
        }

        PlotValidity.Grid grid = PlotValidity.classify(plotPolygon, minX, minZ, maxX, maxZ);

        int bestX = Integer.MIN_VALUE;
        int bestZ = Integer.MIN_VALUE;
        Direction bestTowardBlue = null;
        long bestDistSq = Long.MAX_VALUE;
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                if (grid.at(x, z) != PlotValidity.Cell.GREEN) {
                    continue;
                }
                Direction towardBlue = adjacentBlueDirection(grid, x, z);
                if (towardBlue == null) {
                    continue;
                }
                long dx = x - roadFlagPos.getX();
                long dz = z - roadFlagPos.getZ();
                long distSq = dx * dx + dz * dz;
                if (distSq < bestDistSq) {
                    bestDistSq = distSq;
                    bestX = x;
                    bestZ = z;
                    bestTowardBlue = towardBlue;
                }
            }
        }
        if (bestTowardBlue == null) {
            return null;
        }
        // MOTION_BLOCKING_NO_LEAVES, not WORLD_SURFACE -- WORLD_SURFACE lands on *any* non-air block
        // in the column, including leaves and non-solid plants (tall grass, flowers), so a site under
        // a tree canopy (or sitting on a patch of grass) put the box/sign floating up in the leaves
        // instead of on the real ground (2026-09-30 playtest report, screenshot showing the box/sign
        // spawned inside a tree's canopy). MOTION_BLOCKING_NO_LEAVES skips both.
        int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bestX, bestZ);
        return new Site(new BlockPos(bestX, groundY, bestZ), bestTowardBlue, grid, minX, minZ, maxX, maxZ);
    }

    private static Direction adjacentBlueDirection(PlotValidity.Grid grid, int x, int z) {
        if (grid.at(x, z - 1) == PlotValidity.Cell.BLUE) {
            return Direction.NORTH;
        }
        if (grid.at(x, z + 1) == PlotValidity.Cell.BLUE) {
            return Direction.SOUTH;
        }
        if (grid.at(x + 1, z) == PlotValidity.Cell.BLUE) {
            return Direction.EAST;
        }
        if (grid.at(x - 1, z) == PlotValidity.Cell.BLUE) {
            return Direction.WEST;
        }
        return null;
    }
}
