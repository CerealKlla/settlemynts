package com.github.cerealklla.settlemynts.founding;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.github.cerealklla.cartographyr.geo.Geometry;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Shared logic for turning a block path (a sequence of positions from Cartographyr's
 * {@code supercoverLine} walk) into spawned fence-post entities with correct
 * NORTH/SOUTH/EAST/WEST connection properties -- used by both the settlement perimeter preview
 * ({@link GhostPerimeterFencePostEntity}) and the plot preview ({@code zone.GhostPlotFencePostEntity}),
 * which otherwise differ only in which extra id(s) they tag each spawned entity with (hence the
 * generic {@link Spawner} callback rather than this class spawning entities itself).
 */
public final class FencePostConnections {

    private FencePostConnections() {
    }

    @FunctionalInterface
    public interface Spawner {
        void spawn(ServerLevel level, int x, int y, int z, BlockState state);
    }

    /**
     * For each position in {@code blockPath}, computes a fence {@link BlockState} whose connection
     * properties reflect whether each cardinal neighbor is *also* in the path, then hands it to
     * {@code spawner} at that position's own local ground height. Consecutive positions in a
     * Cartographyr supercover-line walk are always exactly orthogonally adjacent (never a pure
     * diagonal jump), so a diagonal run connects correctly as a proper zig-zagging staircase of
     * fence segments, not a row of disconnected posts.
     */
    public static void spawnConnected(ServerLevel level, List<Geometry.Polygon.Vertex> blockPath, Spawner spawner) {
        Set<Long> present = new HashSet<>();
        for (Geometry.Polygon.Vertex v : blockPath) {
            present.add(pack(v.x(), v.z()));
        }
        for (Geometry.Polygon.Vertex v : blockPath) {
            BlockState state = Blocks.OAK_FENCE.defaultBlockState()
                    .setValue(CrossCollisionBlock.NORTH, present.contains(pack(v.x(), v.z() - 1)))
                    .setValue(CrossCollisionBlock.SOUTH, present.contains(pack(v.x(), v.z() + 1)))
                    .setValue(CrossCollisionBlock.WEST, present.contains(pack(v.x() - 1, v.z())))
                    .setValue(CrossCollisionBlock.EAST, present.contains(pack(v.x() + 1, v.z())));
            int groundY = level.getHeight(Heightmap.Types.WORLD_SURFACE, v.x(), v.z());
            spawner.spawn(level, v.x(), groundY, v.z(), state);
        }
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
