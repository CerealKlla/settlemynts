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

    /** Builds the plot's real Cartographyr polygon from its (angularly-sorted) stakes, rounding each position to the nearest block. */
    public static Geometry.Polygon polygonFromStakes(List<StakePoint> orderedPoints) {
        List<Geometry.Polygon.Vertex> vertices = new ArrayList<>(orderedPoints.size());
        for (StakePoint p : orderedPoints) {
            vertices.add(new Geometry.Polygon.Vertex((int) Math.round(p.x()), (int) Math.round(p.z())));
        }
        return new Geometry.Polygon(vertices);
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
