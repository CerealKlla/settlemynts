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
    void polygonFromStakesTakesTheBlockAStakeIsStandingIn() {
        List<StakePoint> points = List.of(
                new StakePoint(0.4, 0.4), new StakePoint(10.6, 0.4), new StakePoint(5.0, 10.0));

        Geometry.Polygon polygon = PlotGeometry.polygonFromStakes(PlotGeometry.sortAngularly(points));

        assertEquals(new Geometry.Polygon.Vertex(0, 0), polygon.vertices().get(0));
    }

    /**
     * Regression test for a real, deterministic bug (2026-09-27, live playtest report: "the entire
     * plot appears shifted by an entire block in one direction"). Every stake is placed at exactly
     * {@code block + 0.5} in practice ({@code GhostPlotStakeEntity.create}'s own centering) --
     * {@code Math.round} on a value that's always exactly {@code n + 0.5} rounds up to {@code n + 1}
     * every time, shifting the whole polygon by one block. {@code Math.floor} must recover the
     * actual block the stake stood in, not the next one over. Expected vertices are 10 (the low
     * side's own block, unchanged) or 16 (the high side's block, 15, pushed to its own far edge by
     * {@link Geometry.Polygon#coveringBlocks}) -- not 15, since {@link PlotGeometry#polygonFromStakes}
     * now runs every stake through that block-inclusive correction (2026-09-27, see decisions.md).
     */
    @Test
    void polygonFromStakesDoesNotShiftBlockCenteredStakesByOneBlock() {
        List<StakePoint> square = List.of(
                new StakePoint(10.5, 10.5), new StakePoint(15.5, 10.5),
                new StakePoint(15.5, 15.5), new StakePoint(10.5, 15.5));

        Geometry.Polygon polygon = PlotGeometry.polygonFromStakes(PlotGeometry.sortAngularly(square));

        for (Geometry.Polygon.Vertex vertex : polygon.vertices()) {
            assertEquals(true, vertex.x() == 10 || vertex.x() == 16);
            assertEquals(true, vertex.z() == 10 || vertex.z() == 16);
        }
    }

    /**
     * The user's exact spec (2026-09-27): every stake's own block, and every plain interior block,
     * must be equally "inside" -- a stake block is not a special, excluded edge case.
     */
    @Test
    void polygonFromStakesIncludesEveryStakeBlockItself() {
        // A 5x5: stake blocks at 10..14 inclusive on both axes.
        List<StakePoint> square = List.of(
                new StakePoint(10.5, 10.5), new StakePoint(14.5, 10.5),
                new StakePoint(14.5, 14.5), new StakePoint(10.5, 14.5));

        Geometry.Polygon polygon = PlotGeometry.polygonFromStakes(PlotGeometry.sortAngularly(square));

        for (int x = 10; x <= 14; x++) {
            for (int z = 10; z <= 14; z++) {
                assertEquals(true, polygon.contains(x, z), "Expected block (" + x + "," + z + ") to be contained");
            }
        }
    }

    @Test
    void wallVerticesSitOneBlockOutsideTheFullyInclusiveBoundary() {
        List<StakePoint> square = List.of(
                new StakePoint(10.5, 10.5), new StakePoint(14.5, 10.5),
                new StakePoint(14.5, 14.5), new StakePoint(10.5, 14.5));
        Geometry.Polygon polygon = PlotGeometry.polygonFromStakes(PlotGeometry.sortAngularly(square));

        for (Geometry.Polygon.Vertex wallVertex : PlotGeometry.wallVertices(polygon)) {
            // Stake blocks span 10..14 inclusive; the wall ring is the very next block out on each
            // side: 9 (one below the low stake block) and 15 (one above the high stake block).
            assertEquals(true, wallVertex.x() == 9 || wallVertex.x() == 15);
            assertEquals(true, wallVertex.z() == 9 || wallVertex.z() == 15);
            // The wall must never sit on a block the plot itself claims.
            assertEquals(false, polygon.contains(wallVertex.x(), wallVertex.z()));
        }
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
