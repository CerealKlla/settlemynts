package com.github.cerealklla.settlemynts.founding;

import com.github.cerealklla.cartographyr.api.Cartography;
import com.github.cerealklla.cartographyr.geo.Classification;
import com.github.cerealklla.cartographyr.geo.EntityType;
import com.github.cerealklla.cartographyr.geo.GeographicEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Settlement founding (design doc Section 5): the minimum-distance check against existing
 * settlements, and clearing a construction site once a claim flag is validly placed. This is the
 * pure/testable-in-principle half of founding -- {@link SettlementClaimFlagItem} is the thin item
 * wiring that calls into this.
 */
public final class SettlementFounding {

    // Real-world feet-to-block conversion, matching the suite's existing 1 block ~= 1 meter
    // convention (design doc Section 14) -- the user finds mental feet<->meter conversion
    // difficult, so this is done here rather than left in raw feet.
    private static final double BLOCKS_PER_FOOT = 0.3048;

    /** Design doc Section 5: "at least 2000 feet from any kind of Settlement." */
    public static final double MIN_DISTANCE_FEET = 2000.0;
    public static final int MIN_DISTANCE_BLOCKS = (int) Math.round(MIN_DISTANCE_FEET * BLOCKS_PER_FOOT);

    // Design doc Section 5: "a Settlement Construction Site zone of 5x5 land."
    private static final int CONSTRUCTION_SITE_RADIUS = 2;
    // How many blocks of headroom above the local surface get cleared -- enough for most
    // vegetation/small structures, not a guarantee against anything taller.
    private static final int CLEAR_HEIGHT = 5;

    private SettlementFounding() {
    }

    /**
     * Distance in blocks from {@code (x, z)} to the nearest existing settlement (player-founded or
     * natural), or {@link Double#MAX_VALUE} if none exist yet in this dimension. Reuses
     * Cartographyr's existing {@code Cartography.findEntities}/{@code Classification.CONSTRUCTED}
     * query -- no new Cartographyr API needed, since natural villages are already tracked there
     * (design doc Section 1), and a player-founded settlement registers itself there too once
     * finalized (Section 8, not built yet this milestone).
     */
    public static double distanceToNearestSettlement(ServerLevel level, int x, int z) {
        double closest = Double.MAX_VALUE;
        for (GeographicEntity entity : Cartography.findEntities(level, Classification.CONSTRUCTED)) {
            if (!entity.type().equals(EntityType.SETTLEMENT)) {
                continue;
            }
            double dx = entity.geometry().centerBlockX() - x;
            double dz = entity.geometry().centerBlockZ() - z;
            closest = Math.min(closest, Math.sqrt(dx * dx + dz * dz));
        }
        return closest;
    }

    public static boolean isFarEnoughFromExistingSettlements(ServerLevel level, int x, int z) {
        return distanceToNearestSettlement(level, x, z) >= MIN_DISTANCE_BLOCKS;
    }

    /**
     * Clears the 5x5 construction site around {@code center} (design doc Section 5): for each
     * column, removes any non-air block from the local surface up to {@link #CLEAR_HEIGHT} blocks
     * above it. <b>Known v1 scope cut</b>: this only clears obstructions, it does not re-terrain
     * uneven ground (filling holes or leveling bumps) -- the design doc's own "cleared and
     * flattened" language implies real flattening too, deferred to a follow-up pass once this
     * clearing-only version is confirmed working. See decisions.md.
     */
    public static void clearConstructionSite(ServerLevel level, BlockPos center) {
        for (int dx = -CONSTRUCTION_SITE_RADIUS; dx <= CONSTRUCTION_SITE_RADIUS; dx++) {
            for (int dz = -CONSTRUCTION_SITE_RADIUS; dz <= CONSTRUCTION_SITE_RADIUS; dz++) {
                int columnX = center.getX() + dx;
                int columnZ = center.getZ() + dz;
                int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, columnX, columnZ);
                for (int y = surfaceY; y < surfaceY + CLEAR_HEIGHT; y++) {
                    BlockPos pos = new BlockPos(columnX, y, columnZ);
                    if (!level.getBlockState(pos).isAir()) {
                        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
    }
}
