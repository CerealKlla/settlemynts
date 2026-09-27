package com.github.cerealklla.settlemynts.founding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.cerealklla.settlemynts.founding.PerimeterFit.FitResult;
import com.github.cerealklla.settlemynts.founding.PerimeterFit.StakeInput;

/**
 * Covers {@link PerimeterFit} -- pure 2D geometry, no live-world dependency, unlike almost
 * everything else in this mod (see design doc Section 7a).
 */
class PerimeterFitTest {

    private static final double EPSILON = 0.5; // bisection tolerance -- 60 iterations easily beats this

    @Test
    void fittingASquareGrowsItToExactlyTheTargetArea() {
        // A 10x10 square centered on the core (area 100), all non-absolute.
        List<StakeInput> square = List.of(
                new StakeInput(5, 5, false),
                new StakeInput(-5, 5, false),
                new StakeInput(-5, -5, false),
                new StakeInput(5, -5, false));

        FitResult result = PerimeterFit.fit(0, 0, square, 400.0, 1000.0);

        assertEquals(400.0, result.achievedArea(), EPSILON);
    }

    @Test
    void fittingASquareShrinksItToExactlyTheTargetArea() {
        List<StakeInput> square = List.of(
                new StakeInput(10, 10, false),
                new StakeInput(-10, 10, false),
                new StakeInput(-10, -10, false),
                new StakeInput(10, -10, false));

        FitResult result = PerimeterFit.fit(0, 0, square, 100.0, 1000.0);

        assertEquals(100.0, result.achievedArea(), EPSILON);
    }

    @Test
    void absoluteStakesNeverMove() {
        List<StakeInput> square = List.of(
                new StakeInput(5, 5, true), // pinned -- e.g. a riverbank edge
                new StakeInput(-5, 5, false),
                new StakeInput(-5, -5, false),
                new StakeInput(5, -5, false));

        FitResult result = PerimeterFit.fit(0, 0, square, 900.0, 1000.0);

        StakeInput pinned = result.fittedStakes().get(0);
        assertEquals(5.0, pinned.x(), 1e-9);
        assertEquals(5.0, pinned.z(), 1e-9);
        assertTrue(pinned.absolute());
    }

    @Test
    void resultPreservesInputOrder() {
        List<StakeInput> square = List.of(
                new StakeInput(5, 5, false),
                new StakeInput(-5, 5, false),
                new StakeInput(-5, -5, false),
                new StakeInput(5, -5, false));

        FitResult result = PerimeterFit.fit(0, 0, square, 400.0, 1000.0);

        // Same quadrant-relative ordering as the input, not re-sorted into polygon/angular order.
        assertTrue(result.fittedStakes().get(0).x() > 0 && result.fittedStakes().get(0).z() > 0);
        assertTrue(result.fittedStakes().get(1).x() < 0 && result.fittedStakes().get(1).z() > 0);
        assertTrue(result.fittedStakes().get(2).x() < 0 && result.fittedStakes().get(2).z() < 0);
        assertTrue(result.fittedStakes().get(3).x() > 0 && result.fittedStakes().get(3).z() < 0);
    }

    @Test
    void scalingNeverExceedsTheMaxRadiusFromCore() {
        List<StakeInput> square = List.of(
                new StakeInput(5, 5, false),
                new StakeInput(-5, 5, false),
                new StakeInput(-5, -5, false),
                new StakeInput(5, -5, false));

        // Ask for an enormous target area, but cap the radius tightly -- growth should stop at the cap.
        FitResult result = PerimeterFit.fit(0, 0, square, 1_000_000.0, 10.0);

        // The whole-block snap (2026-09-27) can shift a stake's distance from the core by up to
        // sqrt(0.5) blocks past the pre-snap cap -- see PerimeterFit#snapToWholeBlocks's own doc.
        double snapTolerance = Math.sqrt(0.5);
        for (StakeInput stake : result.fittedStakes()) {
            double distance = Math.hypot(stake.x(), stake.z());
            assertTrue(distance <= 10.0 + snapTolerance + 1e-6, "stake at distance " + distance + " exceeded the max radius");
        }
    }

    @Test
    void reachesTheRealSettlementTargetEvenWhenOneStakeStartsNearThePlacementRadius() {
        // Regression test for a real 2026-09-26 playtest bug: reusing the ~152-block placement
        // radius as the fit's own max-radius bound left almost no room to grow whenever a stake
        // happened to already sit near that radius, capping the result well short of the target
        // (observed: 14,327 vs. 20,000 blocks^2). This shape's original area is only 3,400 blocks^2
        // (irregular: three stakes clustered near the core, one "spike" out at distance 150, close
        // to the old placement-radius cap) -- exactly the shape of the real bug, not a regular
        // polygon. Under the old (buggy) 152-block bound this could barely grow past ~3,492; with
        // DEFAULT_MAX_FIT_RADIUS_BLOCKS it must reach the real target almost exactly.
        List<StakeInput> spikeShape = List.of(
                new StakeInput(150, 0, false),
                new StakeInput(0, 20, false),
                new StakeInput(-20, 0, false),
                new StakeInput(0, -20, false));

        FitResult result = PerimeterFit.fit(0, 0, spikeShape, PerimeterFit.DEFAULT_TARGET_AREA_BLOCKS, PerimeterFit.DEFAULT_MAX_FIT_RADIUS_BLOCKS);

        assertEquals(PerimeterFit.DEFAULT_TARGET_AREA_BLOCKS, result.achievedArea(), PerimeterFit.DEFAULT_TARGET_AREA_BLOCKS * 0.01);
    }

    @Test
    void allAbsoluteStakesMeansNoAdjustmentIsPossible() {
        List<StakeInput> square = List.of(
                new StakeInput(5, 5, true),
                new StakeInput(-5, 5, true),
                new StakeInput(-5, -5, true),
                new StakeInput(5, -5, true));

        FitResult result = PerimeterFit.fit(0, 0, square, 999.0, 1000.0);

        assertEquals(100.0, result.achievedArea(), 1e-6); // unchanged 10x10 square
        for (int i = 0; i < square.size(); i++) {
            assertEquals(square.get(i).x(), result.fittedStakes().get(i).x(), 1e-9);
            assertEquals(square.get(i).z(), result.fittedStakes().get(i).z(), 1e-9);
        }
    }

    @Test
    void fewerThanThreeStakesReturnsInputUnchanged() {
        List<StakeInput> twoStakes = List.of(new StakeInput(1, 1, false), new StakeInput(-1, -1, false));

        FitResult result = PerimeterFit.fit(0, 0, twoStakes, 500.0, 1000.0);

        assertEquals(twoStakes, result.fittedStakes());
        assertEquals(0.0, result.achievedArea());
    }

    /**
     * Regression test (2026-09-27, live playtest: "you are calculating non-whole numbers for the
     * expanded stake locations, which is then screwing up everything else") -- the bisection scale
     * factor is an arbitrary real number, so a non-absolute stake's fitted position almost never
     * lands on a whole block on its own without an explicit snap step.
     */
    @Test
    void fittedNonAbsoluteStakesLandOnWholeBlocks() {
        List<StakeInput> square = List.of(
                new StakeInput(5, 5, false),
                new StakeInput(-5, 5, false),
                new StakeInput(-5, -5, false),
                new StakeInput(5, -5, false));

        FitResult result = PerimeterFit.fit(0, 0, square, 777.0, 1000.0);

        for (StakeInput stake : result.fittedStakes()) {
            assertEquals(0.5, stake.x() - Math.floor(stake.x()), 1e-9, "x should be block-centered");
            assertEquals(0.5, stake.z() - Math.floor(stake.z()), 1e-9, "z should be block-centered");
        }
    }

    @Test
    void containsPointDetectsTheCoreInsideAndOutsideASimpleSquare() {
        List<StakeInput> square = PerimeterFit.sortAngularly(List.of(
                new StakeInput(5, 5, false),
                new StakeInput(-5, 5, false),
                new StakeInput(-5, -5, false),
                new StakeInput(5, -5, false)), 0, 0);

        assertTrue(PerimeterFit.containsPoint(square, 0, 0), "the core at the center should be inside");
        assertFalse(PerimeterFit.containsPoint(square, 100, 100), "a far-away point should be outside");
    }
}
