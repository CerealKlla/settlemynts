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
        double distance = polygon.distanceOutside(pos.getX(), pos.getZ());
        return distance > 0.0 && distance <= maxDistanceBlocks;
    }
}
