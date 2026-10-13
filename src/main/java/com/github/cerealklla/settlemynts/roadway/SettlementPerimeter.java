package com.github.cerealklla.settlemynts.roadway;

import com.github.cerealklla.cartographyr.geo.Geometry;
import com.github.cerealklla.cartographyr.geo.GeographicEntity;

import net.minecraft.core.BlockPos;

/**
 * "Just outside a settlement's registered perimeter" -- new geometry needed for Highway Stakes
 * (inter-settlement roads, added 2026-10-11): the first/last stake of a connection must sit just
 * outside each settlement's own edge, per the user's own spec ("Roadway stakes will only
 * 'connected' to the settlement by placing them on the first block outside the perimeter"). Nothing
 * like this existed before -- every other polygon check in this suite ({@code
 * zone.PlotGeometry#isWithinAnyTownProper}, etc.) is a same-settlement *interior* test. Reuses
 * Cartographyr's existing {@code Geometry.Polygon#distanceOutside}, added for the plot-validity
 * feature, rather than re-deriving point-to-polygon distance here.
 */
public final class SettlementPerimeter {

    private SettlementPerimeter() {
    }

    public static boolean isJustOutside(GeographicEntity settlement, BlockPos pos, double maxDistanceBlocks) {
        if (!(settlement.geometry() instanceof Geometry.Polygon polygon)) {
            return false; // A placeholder (PLANNED, Geometry.Point) settlement has no real edge to measure against yet.
        }
        // Real live bug, 2026-10-13: a fully-connected 31-stake chain between two settlements never
        // completed the quest. Live [HighwayAnchorDebug] logging showed both endpoint stakes reading
        // distanceOutside()=0.0 against their own settlement, and the old `distance > 0.0` check
        // rejected both as a result -- distanceOutside() can't tell "sitting exactly on the fitted
        // polygon edge" (which a snug, intentional placement will often land on, via integer
        // rounding) from "well inside the settlement," since both collapse to 0.0 via its own
        // contains()-based short-circuit. distanceToNearestEdge() always measures the real distance
        // to the boundary regardless of which side the point falls on, so a stake within slack of the
        // edge counts as anchoring whether it rounds a hair inside or outside.
        double distance = polygon.distanceToNearestEdge(pos.getX(), pos.getZ());
        return distance <= maxDistanceBlocks;
    }
}
