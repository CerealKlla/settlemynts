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
 * <p>{@link #findNear} fixes this by scanning downward from a caller-supplied {@code referenceY}
 * (plus a small headroom margin for minor slope/elevation differences) instead of the level's
 * absolute ceiling -- the caller is expected to pass a real, already-known-good Y near where the
 * result should land (an existing stake's own position, the settlement core's Y, the average of
 * several real anchor points, etc.), not a fresh guess. Falls back to {@code referenceY} itself
 * (not the level's bottom) if nothing solid turns up in range, since the reference point is always a
 * better guess than the world's floor.
 */
public final class GroundHeightFinder {

    /** Headroom above {@code referenceY} to start scanning from, to tolerate the target column sitting slightly higher than the reference point itself. */
    private static final int SEARCH_MARGIN_ABOVE = 8;

    private GroundHeightFinder() {
    }

    /**
     * Same return convention as {@code Heightmap.Types#getHeight} (the Y of the first open/non-solid
     * space above the real ground), but anchored near {@code referenceY} instead of the level's
     * absolute top -- see this class's own doc for why that distinction matters.
     */
    public static int findNear(ServerLevel level, int x, int z, int referenceY) {
        int top = Math.min(level.getMaxY(), referenceY + SEARCH_MARGIN_ABOVE);
        int bottom = level.getMinY();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, top, z);
        for (int y = top; y >= bottom; y--) {
            pos.setY(y);
            BlockState state = level.getBlockState(pos);
            if (!state.is(BlockTags.LEAVES) && !state.getCollisionShape(level, pos).isEmpty()) {
                return y + 1;
            }
        }
        return referenceY;
    }
}
