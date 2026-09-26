package com.github.cerealklla.settlemynts.founding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.cerealklla.settlemynts.founding.BoundaryWallLayout.Point;
import com.github.cerealklla.settlemynts.founding.PerimeterFit.StakeInput;

class BoundaryWallLayoutTest {

    @Test
    void spacesPointsEvenlyAroundASimpleSquare() {
        // A 10x10 square, perimeter = 40. Spacing 4 -> exactly 10 points.
        List<StakeInput> square = List.of(
                new StakeInput(5, 5, false),
                new StakeInput(-5, 5, false),
                new StakeInput(-5, -5, false),
                new StakeInput(5, -5, false));

        List<Point> points = BoundaryWallLayout.layout(square, 4.0);

        assertEquals(10, points.size());
    }

    @Test
    void fewerThanThreeVerticesProducesNoPoints() {
        List<StakeInput> twoPoints = List.of(new StakeInput(0, 0, false), new StakeInput(1, 1, false));

        assertTrue(BoundaryWallLayout.layout(twoPoints, 4.0).isEmpty());
    }

    @Test
    void veryTightSpacingIsWidenedToRespectTheHardCap() {
        List<StakeInput> square = List.of(
                new StakeInput(500, 500, false),
                new StakeInput(-500, 500, false),
                new StakeInput(-500, -500, false),
                new StakeInput(500, -500, false));

        // A 4000-block perimeter at 1-block spacing would naively want 4000 points.
        List<Point> points = BoundaryWallLayout.layout(square, 1.0);

        assertTrue(points.size() <= BoundaryWallLayout.MAX_WALL_POINTS);
    }
}
