package com.github.cerealklla.settlemynts.zone;

import java.util.ArrayList;
import java.util.List;

import com.github.cerealklla.cartographyr.geo.Geometry;

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
