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

        for (StakeInput stake : result.fittedStakes()) {
            double distance = Math.hypot(stake.x(), stake.z());
            assertTrue(distance <= 10.0 + 1e-6, "stake at distance " + distance + " exceeded the max radius");
        }
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
