package com.github.cerealklla.settlemynts.util;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.tags.BlockTags;

/**
 * Real bug report, 2026-10-29: a Town Hall founded in the Nether had every dependent structure
 * (perimeter stakes/walls, the Plot Config Sign, Construction Box, Road Access Flag...) spawn right
 * under the Nether's bedrock ceiling, nowhere near the actual founding location -- "I have no way to
 * get up to them." Root cause: every one of those call sites used {@code
 * level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)} (this mod's standing "find the
 * real ground, skip tree canopies" convention since 2026-09-30) to resolve a column's ground Y --
 * which always scans down from the absolute top of the level's build height. That's correct in the
 * Overworld (nothing solid exists up there), but in the Nether that scan hits the solid roof
 * immediately, long before it ever reaches the actual terrain near the player.
 *
 * <p><b>Reworked the same day, real follow-up report</b>: "the relocate building, ghost perimeter
 * walls, and other stuff are all having the same issue where they want to go on the surface instead
 * of the nearest free air near the average height for the location." The first version of {@link
 * #findNear} only ever scanned *downward* from {@code referenceY} and landed on the very first solid
 * block it hit -- a one-directional "find the surface below this point" search, not actually "find
 * the nearest open space to this point." That still drags a result up to whatever solid roof/
 * overhang sits directly above a reference point that's genuinely standing in open air (a cave, a
 * tunnel, a settlement built partway up a cliff), and can tunnel straight through solid rock looking
 * for a surface that's actually further away than a walkable gap in the *other* direction. Reworked
 * to mirror {@code roadway.RoadwayPaver#naturalSurfaceYNear}'s already-proven shape (built 2026-10-13
 * for an analogous tunnel-following bug): search outward from {@code referenceY} in both directions
 * at once, returning the first genuinely walkable gap (open space with solid support directly below
 * it) encountered -- literally the nearest free air to the reference height, which is what every
 * caller actually wants "ground near here" to mean. Falls back to the old one-directional downward
 * scan (bounded, not the level's absolute top) only if nothing walkable turns up within {@link
 * #SEARCH_RADIUS_BLOCKS} at all.
 */
public final class GroundHeightFinder {

    // Matches RoadwayPaver/HighwayPaver's own identical constant for the same kind of search --
    // generous enough to find a genuine nearby gap (e.g. the floor of a tunnel/cave a reference
    // point happens to be standing at the mouth of) without reaching into some unrelated terrain
    // feature far away if nothing reasonable exists near the reference at all.
    private static final int SEARCH_RADIUS_BLOCKS = 24;
    /** Headroom for the last-resort downward-only fallback scan, see {@link #fallbackScanDown}. */
    private static final int FALLBACK_MARGIN_ABOVE = 8;

    private GroundHeightFinder() {
    }

    /**
     * Same return convention as {@code Heightmap.Types#getHeight} (the Y of the first open/non-solid
     * space directly above solid ground), but anchored near {@code referenceY} instead of the level's
     * absolute top -- see this class's own doc for the full root-cause writeup and the "search both
     * directions" rework.
     */
    public static int findNear(ServerLevel level, int x, int z, int referenceY) {
        int minY = level.getMinY();
        int maxY = level.getMaxY();
        for (int d = 0; d <= SEARCH_RADIUS_BLOCKS; d++) {
            int up = referenceY + d;
            if (up <= maxY && isWalkableSpace(level, x, up, z)) {
                return up;
            }
            if (d > 0) {
                int down = referenceY - d;
                if (down >= minY && isWalkableSpace(level, x, down, z)) {
                    return down;
                }
            }
        }
        return fallbackScanDown(level, x, z, referenceY);
    }

    /** {@code y} itself is open (non-solid, non-leaf) and {@code y - 1} is real solid support -- i.e. {@code y} is somewhere you could actually stand. */
    private static boolean isWalkableSpace(ServerLevel level, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        return !isSolid(level, pos) && isSolid(level, pos.below());
    }

    private static boolean isSolid(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.is(BlockTags.LEAVES) && !state.getCollisionShape(level, pos).isEmpty();
    }

    /** Last-resort fallback if no genuinely walkable gap exists anywhere within {@link #SEARCH_RADIUS_BLOCKS} -- the original downward-only scan, still bounded near {@code referenceY} rather than the level's absolute top. */
    private static int fallbackScanDown(ServerLevel level, int x, int z, int referenceY) {
        int top = Math.min(level.getMaxY(), referenceY + FALLBACK_MARGIN_ABOVE);
        int bottom = level.getMinY();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, top, z);
        for (int y = top; y >= bottom; y--) {
            pos.setY(y);
            if (isSolid(level, pos)) {
                return y + 1;
            }
        }
        return referenceY;
    }
}
