package com.github.cerealklla.settlemynts.watchtower;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Places/removes the real {@link Blocks#LIGHT} grid a Beacon's current radius calls for -- see
 * {@code WatchTowerConstants#LIGHT_GRID_SPACING_BLOCKS}'s own doc for why 9-block spacing, and
 * {@code WatchTowerBeaconBlockEntity#serverTick} (the only caller) for when this runs (at most once
 * a minute per Beacon, never every tick).
 *
 * <p><b>Never forces a chunk to load</b> -- a real, deliberate lesson from this same session's
 * {@code founding.GhostBoundaryWallEntity} entity-count incident, applied up front here instead of
 * reactively. Computing a grid column's own ground height, or placing/clearing a block there, both
 * force-load the chunk if it isn't already loaded (confirmed against the decompiled source: any
 * {@code Level#setBlock}/{@code getHeight} call resolves the chunk via its own chunk source
 * regardless of load state) -- so every grid column is checked against {@link
 * ServerLevel#hasChunkAt(BlockPos)} *first*. A desired position whose chunk isn't loaded is simply
 * skipped for now; since {@link #recompute} re-derives the full desired set from scratch every
 * minute (pure math plus cheap loaded-chunk checks), an unloaded column gets retried for free on the
 * next pass once its chunk happens to load -- no separate "pending add" bookkeeping is needed. A
 * position that needs *removing* while its chunk is unloaded is different -- it's already a known,
 * real placed block that must not be forgotten, so that case alone is queued in {@link
 * WatchTowerPendingLightRemoval} and resolved later by {@link WatchTowerChunkLoadListener}.
 *
 * <p>Diffing is by exact position, not grid column -- if terrain under an existing column changes
 * height between two recomputes (a player builds something), the old position is cleared and a
 * fresh one placed at the new height, rather than silently leaving a stale light behind.
 */
public final class WatchTowerLightController {

    private WatchTowerLightController() {
    }

    public static void recompute(ServerLevel level, WatchTowerBeaconBlockEntity beacon, int radius) {
        Set<BlockPos> previous = new HashSet<>(beacon.desiredLightPositions());
        List<BlockPos> stillDesired = new ArrayList<>();
        Set<BlockPos> placed = new HashSet<>();

        if (radius > 0) {
            BlockPos center = beacon.getBlockPos();
            int radiusSq = radius * radius;
            for (int dx = -radius; dx <= radius; dx += WatchTowerConstants.LIGHT_GRID_SPACING_BLOCKS) {
                for (int dz = -radius; dz <= radius; dz += WatchTowerConstants.LIGHT_GRID_SPACING_BLOCKS) {
                    if (dx * dx + dz * dz > radiusSq) {
                        continue;
                    }
                    int x = center.getX() + dx;
                    int z = center.getZ() + dz;
                    if (!level.hasChunkAt(new BlockPos(x, 0, z))) {
                        continue; // Retried for free on the next minute's recompute -- see class doc.
                    }
                    int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.isInWorldBounds(pos)) {
                        continue;
                    }
                    boolean alreadyOurs = previous.contains(pos);
                    if (!alreadyOurs && !level.isEmptyBlock(pos)) {
                        continue; // Never overwrite existing terrain/player builds.
                    }
                    if (!alreadyOurs) {
                        level.setBlock(pos, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 15), 3);
                    }
                    stillDesired.add(pos);
                    placed.add(pos);
                }
            }
        }

        for (BlockPos pos : previous) {
            if (!placed.contains(pos)) {
                removeOne(level, pos);
            }
        }

        beacon.setDesiredLightPositions(stillDesired);
    }

    /** Called when the Beacon itself is destroyed/removed -- clears every position it currently owns. */
    public static void removeAll(ServerLevel level, WatchTowerBeaconBlockEntity beacon) {
        for (BlockPos pos : beacon.desiredLightPositions()) {
            removeOne(level, pos);
        }
        beacon.setDesiredLightPositions(List.of());
    }

    private static void removeOne(ServerLevel level, BlockPos pos) {
        if (level.hasChunkAt(pos)) {
            BlockState state = level.getBlockState(pos);
            if (state.is(Blocks.LIGHT)) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
        } else {
            WatchTowerPendingLightRemoval.get(level.getServer()).queue(level.dimension(), pos);
        }
    }
}
