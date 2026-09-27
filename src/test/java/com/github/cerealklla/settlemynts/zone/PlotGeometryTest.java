package com.github.cerealklla.settlemynts.zone;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.cerealklla.cartographyr.geo.Geometry;
import com.github.cerealklla.settlemynts.zone.PlotGeometry.StakePoint;

class PlotGeometryTest {

    @Test
    void centroidIsTheAverageOfAllPoints() {
        List<StakePoint> square = List.of(
                new StakePoint(0, 0), new StakePoint(10, 0),
                new StakePoint(10, 10), new StakePoint(0, 10));

        assertEquals(5.0, PlotGeometry.centroidX(square), 1e-9);
        assertEquals(5.0, PlotGeometry.centroidZ(square), 1e-9);
    }

    @Test
    void sortAngularlyProducesANonSelfIntersectingOrderRegardlessOfInputOrder() {
        // Same square's corners, deliberately shuffled out of a valid winding order.
        List<StakePoint> shuffled = List.of(
                new StakePoint(10, 10), new StakePoint(0, 0),
                new StakePoint(0, 10), new StakePoint(10, 0));

        List<StakePoint> ordered = PlotGeometry.sortAngularly(shuffled);

        Geometry.Polygon polygon = PlotGeometry.polygonFromStakes(ordered);
        // A valid convex quad should contain its own centroid.
        assertEquals(true, polygon.contains(5, 5));
    }

    @Test
    void polygonFromStakesRoundsToNearestBlock() {
        List<StakePoint> points = List.of(
                new StakePoint(0.4, 0.4), new StakePoint(10.6, 0.4), new StakePoint(5.0, 10.0));

        Geometry.Polygon polygon = PlotGeometry.polygonFromStakes(PlotGeometry.sortAngularly(points));

        assertEquals(new Geometry.Polygon.Vertex(0, 0), polygon.vertices().get(0));
    }

    @Test
    void paddedBufferScalesEveryVertexAwayFromCenter() {
        List<StakePoint> square = List.of(
                new StakePoint(-10, -10), new StakePoint(10, -10),
                new StakePoint(10, 10), new StakePoint(-10, 10));
        Geometry.Polygon plot = PlotGeometry.polygonFromStakes(PlotGeometry.sortAngularly(square));

        Geometry.Polygon buffered = PlotGeometry.paddedBuffer(plot, 0.0, 0.0, 5.0);

        // Every original vertex was distance ~14.14 from center; padding by 5 should push each
        // buffered vertex further out than every original vertex, and the buffer should still
        // contain everything the original plot did.
        for (Geometry.Polygon.Vertex v : plot.vertices()) {
            assertEquals(true, buffered.contains(v.x(), v.z()));
        }
        assertEquals(false, plot.contains(12, 12));
        assertEquals(true, buffered.contains(12, 12));
    }
}
