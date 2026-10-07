package com.github.cerealklla.settlemynts.roadway;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import com.github.cerealklla.cartographyr.api.Cartography;
import com.github.cerealklla.cartographyr.geo.Classification;
import com.github.cerealklla.cartographyr.geo.EntityDefinition;
import com.github.cerealklla.cartographyr.geo.EntityType;
import com.github.cerealklla.cartographyr.geo.Geometry;
import com.github.cerealklla.cartographyr.geo.Layer;
import com.github.cerealklla.cartographyr.geo.LifecycleState;
import com.github.cerealklla.settlemynts.SettlemyntsMod;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.registration.ModBlocks;
import com.github.cerealklla.settlemynts.zone.PlotGeometry;
import com.github.cerealklla.settlemynts.zone.PlotRecord;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.HashSet;
import java.util.Set;

/**
 * Roadways Milestone 1's paving algorithm (added 2026-10-06) -- turns two connected {@link
 * RoadwayStakeEntity}s into a real, slope-limited, plot-avoiding Tier 1 road, immediately on
 * connection (not a batch Finalize step, per the user's own "once two Stakes are connected the
 * terrain... is transformed" spec).
 *
 * <p><b>Slope limiting</b>: a two-pass forward/backward relaxation (see {@link
 * #slopeLimitedProfile}) pinned at both stakes' own placed elevations, clamping each step's
 * elevation change to {@link #MAX_SLOPE_PER_STEP} (1 block of rise per 1 block of horizontal
 * travel = 45 degrees), hugging natural terrain wherever the slope allows. Converges within O(n)
 * passes -- cheap, since an edge is bounded by {@link RoadwayStakeEntity#MAX_CONNECTION_LENGTH_BLOCKS}
 * (100 blocks, per the design doc's own signpost-every-100-blocks spec for a finished road).
 *
 * <p><b>Cut vs. causeway</b>: where the limited profile sits below natural terrain, this cuts a
 * clean path down to road level; where it must stay above a dip, this fills a solid support
 * earthwork up to road level (a built-up causeway, not a literal arch/bridge -- a deliberate
 * simplification, see the Milestone 1 plan).
 *
 * <p><b>Plot interaction</b>: {@link #crossesPlot} rejects an edge outright if its *centerline*
 * ever enters a finalized plot's polygon ("two stakes can never directly cross a plot line") --
 * checked by the caller before this method ever creates the stake/link. Only the extra side cells
 * of the 3-wide cross-section narrow near a plot's edge; the centerline itself is never narrowed
 * away, since {@link #crossesPlot} already guarantees it's never inside one.
 *
 * <p><b>Restoring on removal</b> (added 2026-10-06, explicit user request): every block this
 * method touches while paving an edge is captured beforehand (original position + {@link
 * BlockState}) into a {@link SnapshotEntry} list, stored identically on BOTH stakes (see
 * {@link RoadwayStakeEntity#setSnapshotFor}, keyed by the other stake's UUID -- no "the child owns
 * it" asymmetry since the leash-based parent/child model was dropped) so {@link #restoreEdge} can
 * put every one of those blocks back exactly as the server originally generated them when either
 * end of the connection is later removed -- not just clear the road, a genuine before/after revert.
 */
public final class RoadwayPaver {

    private static final int ROAD_SIDE_OFFSET_BLOCKS = 1; // 3-wide: centerline +/- 1.
    private static final int CLEARANCE_BLOCKS = 4;
    private static final double PATH_HALF_WIDTH_BLOCKS = 1.5;

    private RoadwayPaver() {
    }

    /** One block's original state, captured just before {@link #paveColumn} ever touches it. */
    public record SnapshotEntry(BlockPos pos, BlockState state) {
        public static final Codec<SnapshotEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockPos.CODEC.fieldOf("pos").forGetter(SnapshotEntry::pos),
                BlockState.CODEC.fieldOf("state").forGetter(SnapshotEntry::state)
        ).apply(i, SnapshotEntry::new));
    }

    /** Puts every captured block back exactly as it was before the edge was paved -- see {@link RoadwayStakeEntity#remove}. */
    public static void restoreEdge(ServerLevel level, List<SnapshotEntry> snapshot) {
        Set<BlockPos> distinctPositions = new HashSet<>();
        for (SnapshotEntry entry : snapshot) {
            distinctPositions.add(entry.pos());
            level.setBlock(entry.pos(), entry.state(), 3);
        }
        SettlemyntsMod.LOGGER.info(
                "[Roadway] Deleted road: restored {} snapshot entries ({} distinct positions{})",
                snapshot.size(), distinctPositions.size(),
                snapshot.size() != distinctPositions.size()
                        ? ", " + (snapshot.size() - distinctPositions.size()) + " duplicate positions -- last write for each won"
                        : "");
    }

    /**
     * The terrain height this column should pave against -- plain top-of-heightmap, no special
     * handling of an existing {@code ROADWAY} block there.
     *
     * <p><b>Reverted 2026-10-06, same day it was added</b>: an earlier version of this method
     * walked down past any existing Roadway block to find the real dirt underneath, meant to fix a
     * re-pave not cleaning up a previous bad attempt's leftover blocks. That broke a different,
     * more important case -- two roads legitimately crossing -- reported live immediately after:
     * the second road tunneled straight through the first one looking for "natural" ground instead
     * of simply treating the first road's own surface as ground to match or ramp against. An
     * existing Roadway block at a column is now just ground like any other; the correct way to
     * redo a mis-paved edge is to remove that stake (which fully restores the original terrain via
     * its captured {@link SnapshotEntry} list, see {@link RoadwayStakeEntity#remove}) and place it
     * again, not to rely on a repave to clean up after itself.
     */
    private static int naturalSurfaceY(ServerLevel level, int x, int z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
    }

    /**
     * Removes isolated single-column height spikes before slope-limiting (added 2026-10-06, real
     * live report: a visibly "flat" forest floor still produced a staircase of 1-block steps).
     * {@link #slopeLimitedProfile} only ever enforces a slope *ceiling* -- given terrain that
     * legitimately alternates by exactly 1 block between neighboring columns (ground-cover
     * decoration, moss/mycelium micro-variation, etc., all already within the 45 degree cap), it
     * had nothing to actively smooth away, so it faithfully reproduced every single bump as a real
     * step. A median-of-3 filter removes a one-column outlier (it's never the median of itself and
     * its two neighbors) while leaving a genuine multi-column slope untouched, matching the user's
     * own "as smoothly sloped as possible" spec rather than just a hard slope limit.
     */
    private static int[] medianSmooth(int[] values) {
        int n = values.length;
        int[] result = values.clone();
        for (int i = 1; i < n - 1; i++) {
            result[i] = median3(values[i - 1], values[i], values[i + 1]);
        }
        return result;
    }

    private static int median3(int a, int b, int c) {
        return Math.max(Math.min(a, b), Math.min(Math.max(a, b), c));
    }

    /**
     * True if a straight edge between these two ground-level columns would need to rise/fall faster
     * than {@link #MAX_SLOPE_PER_STEP} per horizontal step to connect them -- checked by the caller
     * BEFORE a stake/edge is ever created (added 2026-10-06, real live report: placing a stake almost
     * directly above/below another, with very little horizontal separation, silently produced a
     * near-vertical road). {@link #slopeLimitedProfile} only ever limits the profile's *interior*
     * relative to its own neighbors -- it can't do anything about the two endpoints themselves being
     * too far apart vertically to connect at 45 degrees or less, especially for a short (or
     * 2-point/no-interior) edge where there's no interior to relax at all.
     */
    public static boolean exceedsMaxSlope(int x1, int z1, int y1, int x2, int z2, int y2) {
        int horizontalSteps = Geometry.Polygon.supercoverLine(
                new Geometry.Polygon.Vertex(x1, z1), new Geometry.Polygon.Vertex(x2, z2)).size() - 1;
        if (horizontalSteps <= 0) {
            return y1 != y2;
        }
        return Math.abs(y2 - y1) > horizontalSteps * MAX_SLOPE_PER_STEP;
    }

    /** True if the straight centerline between the two columns ever enters a finalized plot's polygon. */
    public static boolean crossesPlot(ServerLevel level, GhostTownHallCoreEntity core, int x1, int z1, int x2, int z2) {
        for (Geometry.Polygon.Vertex cell : Geometry.Polygon.supercoverLine(
                new Geometry.Polygon.Vertex(x1, z1), new Geometry.Polygon.Vertex(x2, z2))) {
            if (PlotGeometry.findContainingPlot(level, core, cell.x(), cell.z()).isPresent()) {
                return true;
            }
        }
        return false;
    }

    public static void paveEdge(ServerLevel level, GhostTownHallCoreEntity core, RoadwayStakeEntity a, RoadwayStakeEntity b) {
        int x1 = Mth.floor(a.getX());
        int z1 = Mth.floor(a.getZ());
        int x2 = Mth.floor(b.getX());
        int z2 = Mth.floor(b.getZ());
        if (crossesPlot(level, core, x1, z1, x2, z2)) {
            return; // Already rejected by the caller before placement -- defensive no-op, not expected to trigger.
        }

        List<Geometry.Polygon.Vertex> centerline = Geometry.Polygon.supercoverLine(
                new Geometry.Polygon.Vertex(x1, z1), new Geometry.Polygon.Vertex(x2, z2));
        int n = centerline.size();
        int[] naturalY = new int[n];
        for (int i = 0; i < n; i++) {
            Geometry.Polygon.Vertex v = centerline.get(i);
            naturalY[i] = naturalSurfaceY(level, v.x(), v.z());
        }
        // Stakes stand one block ABOVE the ground they're placed on (RoadwayStakeItem places at
        // clickedPos.above(), so the ghost model stands visibly on the surface) -- the pinned road
        // elevation at each endpoint is that ground level, not the stake's own render position.
        // Real bug, live report, 2026-10-06: using the stake's raw Y directly forced the road a full
        // block above natural terrain at both ends, producing a visible "lump" at each stake even on
        // perfectly flat ground.
        int[] profile = slopeLimitedProfile(medianSmooth(naturalY), Mth.floor(a.getY()) - 1, Mth.floor(b.getY()) - 1);

        List<SnapshotEntry> snapshot = new ArrayList<>();
        Set<BlockPos> captured = new HashSet<>();
        int roadBlocksPlaced = 0;
        for (int i = 0; i < n; i++) {
            Geometry.Polygon.Vertex v = centerline.get(i);
            int dx;
            int dz;
            if (i + 1 < n) {
                dx = centerline.get(i + 1).x() - v.x();
                dz = centerline.get(i + 1).z() - v.z();
            } else {
                dx = v.x() - centerline.get(i - 1).x();
                dz = v.z() - centerline.get(i - 1).z();
            }
            int perpX = -Integer.signum(dz);
            int perpZ = Integer.signum(dx);

            for (int offset = -ROAD_SIDE_OFFSET_BLOCKS; offset <= ROAD_SIDE_OFFSET_BLOCKS; offset++) {
                int colX = v.x() + perpX * offset;
                int colZ = v.z() + perpZ * offset;
                if (offset != 0 && PlotGeometry.findContainingPlot(level, core, colX, colZ).isPresent()) {
                    continue; // Narrows the road near a plot's edge -- never the centerline itself.
                }
                int natural = offset == 0 ? naturalY[i] : naturalSurfaceY(level, colX, colZ);
                int targetY = profile[i];
                paveColumn(level, colX, targetY, colZ, natural, snapshot, captured);
                roadBlocksPlaced++;
            }
        }

        var roadEntity = Cartography.createEntity(level, new EntityDefinition(
                level.dimension(), Classification.CONSTRUCTED, EntityType.ROAD, Layer.ROADWAY_ID,
                Optional.empty(), new Geometry.Path(centerline, PATH_HALF_WIDTH_BLOCKS), LifecycleState.REALIZED, Optional.empty()));
        a.setSnapshotFor(b.getUUID(), snapshot, roadEntity.id());
        b.setSnapshotFor(a.getUUID(), snapshot, roadEntity.id());
        RoadwayConnectionMarkerEntity.regenerateAll(level, core.getUUID());

        Set<BlockPos> distinctPositions = new HashSet<>();
        Set<BlockPos> duplicatePositions = new HashSet<>();
        for (SnapshotEntry entry : snapshot) {
            if (!distinctPositions.add(entry.pos())) {
                duplicatePositions.add(entry.pos());
            }
        }
        SettlemyntsMod.LOGGER.info(
                "[Roadway] Added road: {} centerline cells, {} road blocks placed, {} snapshot entries captured "
                        + "({} distinct positions{})",
                n, roadBlocksPlaced, snapshot.size(), distinctPositions.size(),
                duplicatePositions.isEmpty() ? "" : ", " + duplicatePositions.size() + " positions captured more than once: " + duplicatePositions);
    }

    /**
     * Records a position's pre-pave state the FIRST time this edge ever touches it, and silently
     * skips every later touch of the same position (added 2026-10-06, real live bug -- see
     * {@code restoreEdge}'s own doc). Two of this method's own 3-wide cross-sections can land on the
     * same (x,z) near a direction change in the centerline; without this guard, the second capture
     * records the block as this method itself already left it (already paved/cleared), and since
     * {@code restoreEdge} just replays the list in order, that corrupted second entry would silently
     * win over the real original, leaving a leftover scrap of road/stone behind on removal.
     */
    private static void captureOriginal(ServerLevel level, BlockPos pos, List<SnapshotEntry> snapshot, Set<BlockPos> captured) {
        if (captured.add(pos)) {
            snapshot.add(new SnapshotEntry(pos, level.getBlockState(pos)));
        }
    }

    /**
     * Writes {@code desired} at {@code pos} and captures its original state first -- UNLESS it's
     * already exactly {@code desired}, in which case this is a no-op that touches nothing (added
     * 2026-10-06, real live bug report: "place 3 stakes in a row and delete the middle one leaves
     * old road behind"). Every stake's own ground column is the shared endpoint of up to two edges
     * (its incoming edge from a parent, and each outgoing edge to a child) -- the second edge to pave
     * that shared column finds it already a road block at the exact right height (the first edge got
     * there first) and would otherwise capture THAT road block as its own "original" state, which is
     * wrong (the true original is whatever was there before either edge existed). Left uncaptured,
     * that shared column is never claimed by the second edge at all, so removing the FIRST edge alone
     * correctly restores the true original terrain there, undisturbed by the second (still-existing)
     * edge's own, now-skipped, restore entry.
     */
    private static void captureAndSet(ServerLevel level, BlockPos pos, BlockState desired, List<SnapshotEntry> snapshot, Set<BlockPos> captured) {
        if (level.getBlockState(pos).equals(desired)) {
            return;
        }
        captureOriginal(level, pos, snapshot, captured);
        level.setBlock(pos, desired, 3);
    }

    private static void paveColumn(ServerLevel level, int x, int targetY, int z, int naturalY, List<SnapshotEntry> snapshot, Set<BlockPos> captured) {
        BlockState roadState = ModBlocks.ROADWAY.get().defaultBlockState();
        if (targetY > naturalY) {
            // Causeway -- fill a solid support earthwork from natural terrain up to road level.
            for (int y = naturalY; y < targetY; y++) {
                captureAndSet(level, new BlockPos(x, y, z), roadState, snapshot, captured);
            }
        } else if (targetY < naturalY) {
            // Cut -- clear everything from just above road level up through the old natural surface.
            for (int y = targetY + 1; y <= naturalY; y++) {
                BlockPos pos = new BlockPos(x, y, z);
                if (!level.getBlockState(pos).isAir()) {
                    captureOriginal(level, pos, snapshot, captured);
                    level.removeBlock(pos, false);
                }
            }
        }
        captureAndSet(level, new BlockPos(x, targetY, z), roadState, snapshot, captured);
        int clearanceStart = Math.max(targetY + 1, naturalY + 1);
        for (int y = clearanceStart; y <= targetY + CLEARANCE_BLOCKS; y++) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.getBlockState(pos).isAir()) {
                captureOriginal(level, pos, snapshot, captured);
                level.removeBlock(pos, false);
            }
        }
    }

    /**
     * Two-pass forward/backward relaxation: each interior point starts at its own natural terrain
     * height, pinned endpoints stay fixed, and repeated alternating sweeps clamp every interior
     * point to within {@link #MAX_SLOPE_PER_STEP} of its (already-clamped) neighbor until no
     * further change occurs -- standard constraint-propagation shape for "limit the slope of a
     * profile pinned at both ends while hugging a target curve as closely as possible."
     */
    private static final int MAX_SLOPE_PER_STEP = 1;

    private static int[] slopeLimitedProfile(int[] naturalY, int startY, int endY) {
        int n = naturalY.length;
        int[] profile = new int[n];
        System.arraycopy(naturalY, 0, profile, 0, n);
        profile[0] = startY;
        profile[n - 1] = endY;
        if (n <= 2) {
            return profile;
        }
        int passes = Math.max(8, n * 2);
        for (int pass = 0; pass < passes; pass++) {
            boolean changed = false;
            for (int i = 1; i < n - 1; i++) {
                int clamped = Mth.clamp(profile[i], profile[i - 1] - MAX_SLOPE_PER_STEP, profile[i - 1] + MAX_SLOPE_PER_STEP);
                if (clamped != profile[i]) {
                    profile[i] = clamped;
                    changed = true;
                }
            }
            for (int i = n - 2; i >= 1; i--) {
                int clamped = Mth.clamp(profile[i], profile[i + 1] - MAX_SLOPE_PER_STEP, profile[i + 1] + MAX_SLOPE_PER_STEP);
                if (clamped != profile[i]) {
                    profile[i] = clamped;
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        return profile;
    }
}
