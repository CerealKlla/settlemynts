package com.github.cerealklla.settlemynts.founding;

import java.util.ArrayList;
import java.util.List;

/**
 * Places evenly-spaced points along a closed polygon boundary -- the positions {@code
 * GhostBoundaryWallEntity} instances go, for the "View Settlement Boundaries" toggle (design doc
 * Section 9). Pure 2D geometry, no live-world dependency, same shape as {@link PerimeterFit}.
 */
public final class BoundaryWallLayout {

    /**
     * Default spacing between wall points along each edge -- every block (2026-09-26, playtest
     * feedback: 4-block spacing read as "a series of pillars," not a wall). Combined with {@link
     * GhostBoundaryWallEntity#WALL_HEIGHT_BLOCKS}, this produces a real, dense, contiguous-looking
     * wall at the cost of a much higher entity count for a large perimeter -- see {@link
     * #MAX_WALL_POINTS}'s own note.
     */
    public static final double DEFAULT_SPACING_BLOCKS = 1.0;

    /**
     * Hard cap on total wall points regardless of perimeter length or requested spacing -- bounds
     * worst-case entity count for a very large or oddly-shaped perimeter. Raised alongside the
     * spacing change above (2026-09-26) so a typical test-scale settlement's perimeter doesn't
     * trigger the auto-widening fallback and silently space points out again, defeating "every
     * block." Still just a safety net for genuinely large perimeters, not expected to bind for
     * ordinary settlement sizes.
     */
    public static final int MAX_WALL_POINTS = 2000;

    private BoundaryWallLayout() {
    }

    public record Point(double x, double z) {
    }

    /**
     * {@code orderedVertices} must already be in polygon (angular) order -- e.g. {@link
     * PerimeterFit#sortAngularly}. Fewer than 3 vertices returns an empty list (no real boundary to
     * walk). If the requested {@code spacing} would produce more than {@link #MAX_WALL_POINTS}
     * points for this perimeter's actual length, the spacing is widened just enough to stay at the
     * cap, rather than silently truncating the walk partway around (which would leave one section
     * of the boundary with no wall at all).
     */
    public static List<Point> layout(List<PerimeterFit.StakeInput> orderedVertices, double spacing) {
        int n = orderedVertices.size();
        if (n < 3) {
            return List.of();
        }

        double perimeterLength = 0.0;
        for (int i = 0; i < n; i++) {
            perimeterLength += distance(orderedVertices.get(i), orderedVertices.get((i + 1) % n));
        }
        if (perimeterLength <= 0.0) {
            return List.of();
        }

        double effectiveSpacing = spacing;
        int requestedPoints = (int) Math.ceil(perimeterLength / spacing);
        if (requestedPoints > MAX_WALL_POINTS) {
            effectiveSpacing = perimeterLength / MAX_WALL_POINTS;
        }

        List<Point> points = new ArrayList<>();
        double distanceIntoPerimeter = 0.0;
        double nextPointAt = 0.0;
        for (int i = 0; i < n; i++) {
            PerimeterFit.StakeInput a = orderedVertices.get(i);
            PerimeterFit.StakeInput b = orderedVertices.get((i + 1) % n);
            double edgeLength = distance(a, b);
            if (edgeLength <= 0.0) {
                continue;
            }
            // Strict less-than (with a small epsilon) so the very last candidate point of the
            // final edge -- which lands exactly back on the closed loop's starting point -- isn't
            // re-added as a duplicate.
            while (nextPointAt < distanceIntoPerimeter + edgeLength - 1e-9 && points.size() < MAX_WALL_POINTS) {
                double along = (nextPointAt - distanceIntoPerimeter) / edgeLength;
                points.add(new Point(a.x() + along * (b.x() - a.x()), a.z() + along * (b.z() - a.z())));
                nextPointAt += effectiveSpacing;
            }
            distanceIntoPerimeter += edgeLength;
        }
        return points;
    }

    private static double distance(PerimeterFit.StakeInput a, PerimeterFit.StakeInput b) {
        return Math.hypot(b.x() - a.x(), b.z() - a.z());
    }
}
