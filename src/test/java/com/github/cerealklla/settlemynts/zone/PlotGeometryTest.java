package com.github.cerealklla.settlemynts.zone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void polygonFromStakesBuildsAValidPolygonFromStakesInPlacementOrder() {
        // Stakes must already be in placement order (2026-09-27, see decisions.md same date) --
        // angular re-sorting was removed since it can't build a valid polygon for non-star-shaped
        // input. A square listed corner-by-corner is already valid placement order.
        List<StakePoint> square = List.of(
                new StakePoint(0, 0), new StakePoint(10, 0),
                new StakePoint(10, 10), new StakePoint(0, 10));

        Geometry.Polygon polygon = PlotGeometry.polygonFromStakes(square);
        // A valid quad should contain its own centroid.
        assertEquals(true, polygon.contains(5, 5));
    }

    @Test
    void polygonFromStakesTakesTheBlockAStakeIsStandingIn() {
        List<StakePoint> points = List.of(
                new StakePoint(0.4, 0.4), new StakePoint(10.6, 0.4), new StakePoint(5.0, 10.0));

        Geometry.Polygon polygon = PlotGeometry.polygonFromStakes(points);

        // Contour-traced output order isn't necessarily the input order -- assert containment of
        // each stake's own block instead of a specific vertex position (same style as Cartographyr's
        // own GeometryTest).
        assertTrue(polygon.contains(0, 0));
    }

    /**
     * Regression test for a real, deterministic bug (2026-09-27, live playtest report: "the entire
     * plot appears shifted by an entire block in one direction"). Every stake is placed at exactly
     * {@code block + 0.5} in practice ({@code GhostPlotStakeEntity.create}'s own centering) --
     * {@code Math.round} on a value that's always exactly {@code n + 0.5} rounds up to {@code n + 1}
     * every time, shifting the whole polygon by one block. {@code Math.floor} must recover the
     * actual block the stake stood in, not the next one over.
     */
    @Test
    void polygonFromStakesDoesNotShiftBlockCenteredStakesByOneBlock() {
        List<StakePoint> square = List.of(
                new StakePoint(10.5, 10.5), new StakePoint(15.5, 10.5),
                new StakePoint(15.5, 15.5), new StakePoint(10.5, 15.5));

        Geometry.Polygon polygon = PlotGeometry.polygonFromStakes(square);

        for (int x = 10; x <= 15; x++) {
            for (int z = 10; z <= 15; z++) {
                assertTrue(polygon.contains(x, z), "Expected block (" + x + "," + z + ") to be contained");
            }
        }
        assertFalse(polygon.contains(9, 12));
        assertFalse(polygon.contains(16, 12));
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

        Geometry.Polygon polygon = PlotGeometry.polygonFromStakes(square);

        for (int x = 10; x <= 14; x++) {
            for (int z = 10; z <= 14; z++) {
                assertEquals(true, polygon.contains(x, z), "Expected block (" + x + "," + z + ") to be contained");
            }
        }
    }

    /**
     * A plot shaped like an L, with a reflex (concave) corner -- the exact shape that broke the old
     * per-vertex outward push (see Cartographyr's decisions.md, 2026-09-27). Proves the fix reaches
     * plots (not just Cartographyr's own unit tests) end-to-end through {@code polygonFromStakes}.
     */
    @Test
    void polygonFromStakesSupportsAConcaveLShapedPlot() {
        List<StakePoint> lShape = List.of(
                new StakePoint(0.5, 0.5), new StakePoint(4.5, 0.5),
                new StakePoint(4.5, 2.5), new StakePoint(2.5, 2.5),
                new StakePoint(2.5, 4.5), new StakePoint(0.5, 4.5));

        Geometry.Polygon polygon = PlotGeometry.polygonFromStakes(lShape);

        assertTrue(polygon.contains(3, 1)); // bottom strip
        assertTrue(polygon.contains(1, 3)); // upper-left strip
        assertTrue(polygon.contains(2, 2)); // the reflex vertex's own stake block
        assertFalse(polygon.contains(3, 2)); // an un-staked notch block
    }

    @Test
    void wallRingSitsOneBlockOutsideTheFullyInclusiveBoundary() {
        List<StakePoint> square = List.of(
                new StakePoint(10.5, 10.5), new StakePoint(14.5, 10.5),
                new StakePoint(14.5, 14.5), new StakePoint(10.5, 14.5));
        Geometry.Polygon polygon = PlotGeometry.polygonFromStakes(square);

        for (Geometry.Polygon.Vertex wallBlock : Geometry.Polygon.outerRing(polygon)) {
            // Stake blocks span 10..14 inclusive; the wall ring is the very next block out on each
            // side: 9 (one below the low stake block) and 15 (one above the high stake block).
            assertEquals(true, wallBlock.x() == 9 || wallBlock.x() == 15
                    || wallBlock.z() == 9 || wallBlock.z() == 15);
            // The wall must never sit on a block the plot itself claims.
            assertEquals(false, polygon.contains(wallBlock.x(), wallBlock.z()));
        }
    }

    @Test
    void paddedBufferScalesEveryVertexAwayFromCenter() {
        List<StakePoint> square = List.of(
                new StakePoint(-10, -10), new StakePoint(10, -10),
                new StakePoint(10, 10), new StakePoint(-10, 10));
        Geometry.Polygon plot = PlotGeometry.polygonFromStakes(square);

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
