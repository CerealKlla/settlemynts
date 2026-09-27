package com.github.cerealklla.settlemynts.zone;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.github.cerealklla.cartographyr.geo.Geometry;

/**
 * Pure 2D geometry for turning a plot's placed stakes into Cartographyr polygons -- no live-world
 * dependency, so unlike almost everything else in this mod it's genuinely unit-testable (same
 * precedent as {@code founding.PerimeterFit}/{@code founding.BoundaryWallLayout}).
 *
 * <p>Deliberately simpler than {@code PerimeterFit}: a plot's shape is exactly what the Town
 * Planner drew with their stakes -- no auto-fit/target-area expansion, since (unlike a settlement's
 * perimeter) there's no "every plot should be the same size" goal here. Angular ordering around the
 * plot's own centroid is still needed to turn an unordered set of placed points into a valid,
 * non-self-intersecting polygon.
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

    /** Angular order around the points' own centroid -- turns an unordered set of placed stakes into a valid polygon vertex sequence. Needs at least 3 points to mean anything; callers are expected to have already checked that. */
    public static List<StakePoint> sortAngularly(List<StakePoint> points) {
        double centerX = centroidX(points);
        double centerZ = centroidZ(points);
        List<StakePoint> sorted = new ArrayList<>(points);
        sorted.sort(Comparator.comparingDouble(p -> Math.atan2(p.z() - centerZ, p.x() - centerX)));
        return sorted;
    }

    /**
     * Builds the plot's real Cartographyr polygon from its (angularly-sorted) stakes, taking each
     * position's own block column ({@code Math.floor}, not {@code Math.round}), then handing the
     * result to {@link Geometry.Polygon#coveringBlocks} so every stake's own block -- corners
     * included -- resolves as inside the plot, per the user's exact spec (2026-09-27): a stake block
     * and a plain interior block must be equally "permitted," only the wall ring one block further
     * out is not.
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
     * The wall's own trace polygon -- the block ring immediately outside the plot's real, already
     * block-inclusive boundary ({@link #polygonFromStakes}'s output), per the user's spec: the wall
     * marks the first NOT-permitted ring, so it must never sit on a block the plot itself already
     * claims. {@code plotPolygon}'s vertices are edge coordinates, not block ids -- on the outward
     * side of a given axis at a vertex, that vertex already sits at its stake block's own low edge
     * (e.g. block 10 -> edge 10), so the next block out on that side is {@code edge - 1}; on the
     * inward-relative-to-that-axis side {@link Geometry.Polygon#coveringBlocks} already pushed the
     * vertex to the far edge of the outermost stake block (e.g. stake block 14 -> edge 15), and that
     * edge coordinate *is* the next block out (block 15), unchanged. Which side is "outward" is
     * decided per axis by {@link Geometry.Polygon#localOutwardNormals}, not by comparing to the
     * whole polygon's centroid (fixed 2026-09-27, live playtest: a centroid comparison distorted
     * diagonal runs on a many-vertex settlement shape into a visible lump/hump -- see decisions.md
     * same date). Same edge-tangent-aware approach {@code coveringBlocks} itself uses.
     */
    public static List<Geometry.Polygon.Vertex> wallVertices(Geometry.Polygon plotPolygon) {
        List<Geometry.Polygon.Vertex> vertices = plotPolygon.vertices();
        List<Geometry.Polygon.Normal> normals = Geometry.Polygon.localOutwardNormals(vertices);

        List<Geometry.Polygon.Vertex> wall = new ArrayList<>(vertices.size());
        for (int i = 0; i < vertices.size(); i++) {
            Geometry.Polygon.Vertex v = vertices.get(i);
            Geometry.Polygon.Normal normal = normals.get(i);
            int wallX = normal.x() >= 0 ? v.x() : v.x() - 1;
            int wallZ = normal.z() >= 0 ? v.z() : v.z() - 1;
            wall.add(new Geometry.Polygon.Vertex(wallX, wallZ));
        }
        return wall;
    }

    /**
     * The plot's "Town Proper" buffer polygon (design doc Section 11a) -- each vertex scaled
     * outward from the plot's own centroid by {@code paddingBlocks}, the same radial-padding
     * approximation {@code SettlemyntsMod#registerWithCartographyr} already uses for a settlement's
     * own buffer (adequate for the same reason: a plot's polygon is star-shaped around its centroid
     * by construction, since every vertex was angularly sorted around it).
     */
    public static Geometry.Polygon paddedBuffer(Geometry.Polygon plot, double centerX, double centerZ, double paddingBlocks) {
        List<Geometry.Polygon.Vertex> padded = new ArrayList<>(plot.vertices().size());
        for (Geometry.Polygon.Vertex vertex : plot.vertices()) {
            double dx = vertex.x() - centerX;
            double dz = vertex.z() - centerZ;
            double distance = Math.hypot(dx, dz);
            double scale = distance > 1.0e-9 ? (distance + paddingBlocks) / distance : 1.0;
            padded.add(new Geometry.Polygon.Vertex(
                    (int) Math.round(centerX + dx * scale),
                    (int) Math.round(centerZ + dz * scale)));
        }
        return new Geometry.Polygon(padded);
    }
}
