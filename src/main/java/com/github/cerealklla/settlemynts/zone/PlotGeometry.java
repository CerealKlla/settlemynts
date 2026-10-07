package com.github.cerealklla.settlemynts.zone;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.github.cerealklla.cartographyr.api.Cartography;
import com.github.cerealklla.cartographyr.geo.EntityId;
import com.github.cerealklla.cartographyr.geo.GeographicEntity;
import com.github.cerealklla.cartographyr.geo.Geometry;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;

import net.minecraft.server.level.ServerLevel;

/**
 * Pure 2D geometry for turning a plot's placed stakes into Cartographyr polygons -- no live-world
 * dependency, so unlike almost everything else in this mod it's genuinely unit-testable (same
 * precedent as {@code founding.PerimeterFit}).
 *
 * <p>Deliberately simpler than {@code PerimeterFit}: a plot's shape is exactly what the Town
 * Planner drew with their stakes -- no auto-fit/target-area expansion, since (unlike a settlement's
 * perimeter) there's no "every plot should be the same size" goal here. Stakes are connected in
 * **placement order** (the actual sequence they were placed in), not re-sorted by angle around a
 * centroid -- angular sorting only produces a valid polygon when the shape is star-shaped from its
 * centroid (true for a rectangle/blob, false for a C-shape, ring, or anything whose centroid sits
 * outside the material); see decisions.md 2026-09-27 for the full reasoning and the user's question
 * that surfaced it. Placement order is tracked directly on {@code GhostPlotStakeEntity}, not
 * recomputed here.
 */
public final class PlotGeometry {

    private PlotGeometry() {
    }

    /** One stake's world position (a plot has no "absolute" concept -- every stake is just a drawn point). */
    public record StakePoint(double x, double z) {
    }

    public static double centroidX(List<StakePoint> points) {
        double sum = 0.0;
        for (StakePoint p : points) {
            sum += p.x();
        }
        return sum / points.size();
    }

    public static double centroidZ(List<StakePoint> points) {
        double sum = 0.0;
        for (StakePoint p : points) {
            sum += p.z();
        }
        return sum / points.size();
    }

    /**
     * Builds the plot's real Cartographyr polygon from its stakes, taking each position's own block
     * column ({@code Math.floor}, not {@code Math.round}), then handing the result to {@link
     * Geometry.Polygon#coveringBlocks} so every stake's own block -- corners included -- resolves as
     * inside the plot, per the user's exact spec (2026-09-27): a stake block and a plain interior
     * block must be equally "permitted," only the wall ring one block further out is not.
     *
     * <p>{@code orderedPoints} must already be in **placement order** (see class doc) -- this method
     * does not sort them itself.
     *
     * <p><b>{@code Math.round} was a real, deterministic bug here</b> (fixed 2026-09-27, a live
     * playtest report -- "the entire plot appears shifted by an entire block in one direction"):
     * every stake is placed at exactly {@code block + 0.5} ({@code GhostPlotStakeEntity.create}'s
     * own `+0.5` centering), and {@code Math.round} on a value that's always exactly `n + 0.5`
     * rounds up to `n + 1` *every single time*, for every stake, in both X and Z -- not an
     * occasional off-by-one, a guaranteed whole-polygon shift by one block. {@code Math.floor}
     * correctly recovers the block the stake was actually standing in.
     */
    public static Geometry.Polygon polygonFromStakes(List<StakePoint> orderedPoints) {
        List<Geometry.Polygon.Vertex> vertices = new ArrayList<>(orderedPoints.size());
        for (StakePoint p : orderedPoints) {
            vertices.add(new Geometry.Polygon.Vertex((int) Math.floor(p.x()), (int) Math.floor(p.z())));
        }
        return Geometry.Polygon.coveringBlocks(vertices);
    }

    /**
     * The plot's "Town Proper" buffer polygon (design doc Section 11a) -- {@code plot} grown outward
     * by {@code paddingBlocks} via Cartographyr's {@link Geometry.Polygon#expandedBy}, which follows
     * the plot's own shape rather than cutting across a concave notch. Replaced a radial-scale-from-
     * centroid version (2026-09-27, live playtest: a C-shaped plot's buffer cut straight across the
     * C's open notch instead of following it -- scaling every vertex away from one shared center
     * point has no notion of the shape's own concavity).
     */
    public static Geometry.Polygon paddedBuffer(Geometry.Polygon plot, double paddingBlocks) {
        return Geometry.Polygon.expandedBy(plot, (int) Math.round(paddingBlocks));
    }

    /**
     * True if {@code (x, z)} is inside {@code polygon} but has at least one orthogonal neighbor that
     * isn't -- an interior cell right on the boundary, not deep inside. Shared between {@code
     * RoadAccessFlagItem}'s own placement validation and {@code GhostRoadAccessPreviewEntity}'s live
     * "where can this go" preview (2026-09-29), so the two can never silently disagree.
     */
    public static boolean isOnPerimeter(Geometry.Polygon polygon, int x, int z) {
        if (!polygon.contains(x, z)) {
            return false;
        }
        return !polygon.contains(x - 1, z) || !polygon.contains(x + 1, z)
                || !polygon.contains(x, z - 1) || !polygon.contains(x, z + 1);
    }

    /** Every perimeter cell (see {@link #isOnPerimeter}) within {@code polygon}'s own bounding box. */
    public static List<Geometry.Polygon.Vertex> perimeterCells(Geometry.Polygon polygon) {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Geometry.Polygon.Vertex v : polygon.vertices()) {
            minX = Math.min(minX, v.x());
            minZ = Math.min(minZ, v.z());
            maxX = Math.max(maxX, v.x());
            maxZ = Math.max(maxZ, v.z());
        }
        List<Geometry.Polygon.Vertex> cells = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (isOnPerimeter(polygon, x, z)) {
                    cells.add(new Geometry.Polygon.Vertex(x, z));
                }
            }
        }
        return cells;
    }

    /**
     * The first of {@code core}'s already-finalized plots whose real Cartographyr polygon contains
     * {@code (x, z)}, or empty if none does -- added 2026-09-30 to block placing a new Plot
     * Placement Stake inside an existing plot, and reused to find "the Town Hall Plot a Core
     * currently sits in" for Reposition Town Hall Core. A plot missing its Cartographyr entity
     * (shouldn't happen for an already-finalized plot, but not assumed) is simply skipped, not
     * treated as an error.
     */
    public static Optional<PlotRecord> findContainingPlot(ServerLevel level, GhostTownHallCoreEntity core, int x, int z) {
        for (PlotRecord plot : core.getPlots()) {
            Optional<GeographicEntity> entity = Cartography.getEntity(level, new EntityId(plot.cartographyrPlotEntityId()));
            if (entity.isPresent() && entity.get().geometry() instanceof Geometry.Polygon polygon && polygon.contains(x, z)) {
                return Optional.of(plot);
            }
        }
        return Optional.empty();
    }

    /**
     * True if {@code (x, z)} falls inside ANY of {@code core}'s finalized plots' own "Town Proper"
     * buffers -- not just one specific plot's (added 2026-10-06, Guard road-patrol: "guards can
     * follow any road within a settlement so long as their current position doesn't leave a 'Town
     * Proper' position... they only protect buildings people can walk to within the town itself").
     * Unlike {@link #findContainingPlot} (one specific plot's own polygon), this deliberately checks
     * every plot's padded BUFFER, since a settlement-spanning road routinely passes near several
     * different plots, not just the one a given guard's own Guardhouse happens to sit on.
     */
    public static boolean isWithinAnyTownProper(ServerLevel level, GhostTownHallCoreEntity core, int x, int z) {
        for (PlotRecord plot : core.getPlots()) {
            Optional<GeographicEntity> entity = Cartography.getEntity(level, new EntityId(plot.cartographyrBufferEntityId()));
            if (entity.isPresent() && entity.get().geometry() instanceof Geometry.Polygon polygon && polygon.contains(x, z)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True if {@code a} and {@code b} share any block column -- added 2026-09-30 as Finalize's own
     * redundant safety net against two Town Planners staking overlapping plots concurrently (neither
     * sees the other's in-progress stakes). No polygon-vs-polygon primitive exists in Cartographyr's
     * own {@code geo} package (only {@code Polygon#contains(x, z)}, a single-point test), so this is
     * a Settlemynts-local helper: reject fast on non-overlapping bounding boxes, otherwise
     * exhaustively scan every integer column in the intersected bounding box -- cheap and exactly
     * correct at the same block-column resolution {@code cartographyr.geo.PlotValidity#classify}
     * already operates at, and only ever run once per Finalize attempt, not per-tick.
     */
    public static boolean overlaps(Geometry.Polygon a, Geometry.Polygon b) {
        int aMinX = Integer.MAX_VALUE, aMinZ = Integer.MAX_VALUE, aMaxX = Integer.MIN_VALUE, aMaxZ = Integer.MIN_VALUE;
        for (Geometry.Polygon.Vertex v : a.vertices()) {
            aMinX = Math.min(aMinX, v.x());
            aMinZ = Math.min(aMinZ, v.z());
            aMaxX = Math.max(aMaxX, v.x());
            aMaxZ = Math.max(aMaxZ, v.z());
        }
        int bMinX = Integer.MAX_VALUE, bMinZ = Integer.MAX_VALUE, bMaxX = Integer.MIN_VALUE, bMaxZ = Integer.MIN_VALUE;
        for (Geometry.Polygon.Vertex v : b.vertices()) {
            bMinX = Math.min(bMinX, v.x());
            bMinZ = Math.min(bMinZ, v.z());
            bMaxX = Math.max(bMaxX, v.x());
            bMaxZ = Math.max(bMaxZ, v.z());
        }
        int minX = Math.max(aMinX, bMinX);
        int minZ = Math.max(aMinZ, bMinZ);
        int maxX = Math.min(aMaxX, bMaxX);
        int maxZ = Math.min(aMaxZ, bMaxZ);
        if (minX > maxX || minZ > maxZ) {
            return false;
        }
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (a.contains(x, z) && b.contains(x, z)) {
                    return true;
                }
            }
        }
        return false;
    }
}
