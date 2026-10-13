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
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.HashSet;
import java.util.Set;

/**
 * Roadways Milestone 1's paving algorithm (added 2026-10-06) -- turns two connected {@link
 * RoadwayStakeEntity}s into a real, slope-limited, plot-avoiding road, immediately on connection
 * (not a batch Finalize step, per the user's own "once two Stakes are connected the terrain... is
 * transformed" spec). The road's visual look (not its shape/path) is tied to the settlement's
 * current Town Hall Tier as of 2026-10-09 -- see {@link RoadwayTierResolver}.
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

    // How far up/down to search for the real walkable surface nearest a reference height -- generous
    // enough to find the floor of a tunnel dug under a tall hill, without reaching into some unrelated
    // deep cave far below if nothing reasonable exists near the reference at all.
    private static final int SURFACE_SEARCH_RADIUS_BLOCKS = 24;

    /**
     * The real walkable surface (solid block with open air directly above it) nearest {@code
     * referenceY} at this column -- real live report, 2026-10-13: "if I dig a tunnel through a hill
     * and place a stake on the ground (same height), the road should follow the boundary of air and
     * solid ground between the stakes... What is happening right now is it seems like you are simply
     * replacing the sky facing block with road surface, making my tunnel impossible." {@link
     * #naturalSurfaceY} always found the world's topmost surface via a heightmap, completely blind to
     * any tunnel dug beneath it -- correct for two roads crossing at grade (see that method's own
     * doc), wrong for an actual tunnel. Searching outward from a reference height (the interpolated
     * straight-line height between the two stakes at this point) instead finds whichever real surface
     * -- a tunnel floor, a road already there, open ground -- is actually nearest that reference,
     * which satisfies both cases: a tunnel's own floor is right at the reference height and wins
     * immediately, while a crossing road sitting at a similar grade is still the nearest match. Falls
     * back to the old topmost-surface sample only if nothing walkable is found within {@link
     * #SURFACE_SEARCH_RADIUS_BLOCKS} of the reference at all (e.g. the stakes are floating with no
     * real terrain nearby).
     */
    private static int naturalSurfaceYNear(ServerLevel level, int x, int z, int referenceY) {
        int minY = level.getMinY();
        int maxY = level.getMaxY();
        for (int d = 0; d <= SURFACE_SEARCH_RADIUS_BLOCKS; d++) {
            int up = referenceY + d;
            if (up <= maxY && isWalkableSurface(level, x, up, z)) {
                return up;
            }
            if (d > 0) {
                int down = referenceY - d;
                if (down >= minY && isWalkableSurface(level, x, down, z)) {
                    return down;
                }
            }
        }
        return naturalSurfaceY(level, x, z);
    }

    private static boolean isWalkableSurface(ServerLevel level, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        return !level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir();
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

    /** Widened 2026-10-09 to pave at the settlement's current Town Hall Tier look (see {@link RoadwayTierResolver}) instead of a single fixed appearance. */
    public static void paveEdge(ServerLevel level, GhostTownHallCoreEntity core, RoadwayStakeEntity a, RoadwayStakeEntity b) {
        int tier = RoadwayTierResolver.currentTier(level, core);
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
        // Stakes stand one block ABOVE the ground they're placed on (RoadwayStakeItem places at
        // clickedPos.above(), so the ghost model stands visibly on the surface) -- the pinned road
        // elevation at each endpoint is that ground level, not the stake's own render position.
        // Real bug, live report, 2026-10-06: using the stake's raw Y directly forced the road a full
        // block above natural terrain at both ends, producing a visible "lump" at each stake even on
        // perfectly flat ground.
        int startY = Mth.floor(a.getY()) - 1;
        int endY = Mth.floor(b.getY()) - 1;
        int[] naturalY = new int[n];
        for (int i = 0; i < n; i++) {
            Geometry.Polygon.Vertex v = centerline.get(i);
            // Reference height for the surface search below -- the straight-line interpolation
            // between the two stakes' own ground level at this point, so a tunnel floor (right at
            // this reference) is found instead of the hill's distant outer surface. See
            // #naturalSurfaceYNear's own doc.
            int referenceY = n <= 1 ? startY : Math.round(startY + (endY - startY) * (i / (float) (n - 1)));
            naturalY[i] = naturalSurfaceYNear(level, v.x(), v.z(), referenceY);
        }
        int[] profile = slopeLimitedProfile(medianSmooth(naturalY), startY, endY);

        List<SnapshotEntry> snapshot = new ArrayList<>();
        List<BlockPos> roadCells = new ArrayList<>();
        Set<BlockPos> captured = new HashSet<>();
        // Dedupes which physical (x,z) column each centerline index is allowed to pave (added
        // 2026-10-12, real live report: "for angled roads it seems to be determining that height
        // from the highest point of the road and using it along the entire length"). A perfectly
        // cardinal (N/S/E/W) road never changes direction mid-edge, so its 3-wide cross-sections
        // never overlap -- but `supercoverLine` zigzags for any genuinely angled/diagonal edge,
        // and the perpendicular cross-section at a direction-change index can land squarely on a
        // column an earlier index already claimed. Without this guard, that column's target/
        // clearance height got silently reprocessed and overwritten by whichever index happened to
        // touch it LAST (not necessarily the nearer one) -- order-dependent, and on a long uphill
        // angled edge, systematically biased toward whatever later (generally higher) index won.
        // First-touch-wins makes the outcome deterministic and keeps each column's own paved
        // height tied to its nearest real centerline point.
        Set<BlockPos> pavedColumns = new HashSet<>();
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

            // Slope-step stair, added 2026-10-12 (explicit user request, Tier 3+ only) -- wherever
            // this index sits exactly 1 block above its immediate predecessor on the (already
            // slope-limited, so never more than 1) profile, this whole 3-wide cross-section paves as
            // a real vanilla stairs block climbing in the direction of travel instead of a flat cube
            // with a vertical riser between it and the previous cross-section. See
            // #stairStateFor's own doc for which tiers actually have a real vanilla stairs match.
            BlockState stepState = null;
            if (i > 0 && profile[i] != profile[i - 1]) {
                Direction travelDirection = directionFromDelta(v.x() - centerline.get(i - 1).x(), v.z() - centerline.get(i - 1).z());
                if (profile[i] < profile[i - 1]) {
                    travelDirection = travelDirection.getOpposite(); // Descending -- the riser (and climb direction) point the other way.
                }
                stepState = stairStateFor(tier, travelDirection);
            }
            // Real live report, 2026-10-13 (screenshots): "the stairs on the downward slope are a
            // full block too low." A single vanilla stair block's own placed Y is its LOW side --
            // its high/full-height corner reaches placedY + 1, which is where it must meet the next
            // flat landing. This cross-section used to place the stair at the raw profile[i] value
            // unconditionally, same as every flat column -- correct when descending from i-1 to i
            // (profile[i] already equals the lower of the two, i.e. min(profile[i], profile[i-1])),
            // but one block too HIGH when ASCENDING from i-1 to i (profile[i] is the higher of the
            // two there, so the stair's low side sat flush with the upper landing instead of the
            // lower one, leaving a real one-block gap/overlap depending on which direction a player
            // walks it). This is why it looked order-dependent ("based on which stake is connected
            // first") -- paving always runs from the anchor stake to the new one, so whichever
            // physical direction that happens to be is the one direction that was wrong.
            int pavedY = stepState != null ? Math.min(profile[i], profile[i - 1]) : profile[i];

            for (int offset = -ROAD_SIDE_OFFSET_BLOCKS; offset <= ROAD_SIDE_OFFSET_BLOCKS; offset++) {
                int colX = v.x() + perpX * offset;
                int colZ = v.z() + perpZ * offset;
                if (offset != 0 && PlotGeometry.findContainingPlot(level, core, colX, colZ).isPresent()) {
                    continue; // Narrows the road near a plot's edge -- never the centerline itself.
                }
                BlockPos columnKey = new BlockPos(colX, 0, colZ);
                if (!pavedColumns.add(columnKey)) {
                    continue; // Already paved by an earlier (nearer) centerline index -- see pavedColumns' own doc.
                }
                int targetY = pavedY;
                int natural = offset == 0 ? naturalY[i] : naturalSurfaceYNear(level, colX, colZ, targetY);
                paveColumn(level, colX, targetY, colZ, natural, tier, stepState, snapshot, captured);
                roadCells.add(new BlockPos(colX, targetY, colZ));
                roadBlocksPlaced++;
            }
        }

        var roadEntity = Cartography.createEntity(level, new EntityDefinition(
                level.dimension(), Classification.CONSTRUCTED, EntityType.ROAD, Layer.ROADWAY_ID,
                Optional.empty(), new Geometry.Path(centerline, PATH_HALF_WIDTH_BLOCKS), LifecycleState.REALIZED, Optional.empty()));
        a.setSnapshotFor(b.getUUID(), snapshot, roadEntity.id(), roadCells);
        b.setSnapshotFor(a.getUUID(), snapshot, roadEntity.id(), roadCells);
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

    /**
     * The direction a stair at this tier should climb, or {@code null} for a tier with no real
     * vanilla stairs match -- Tier 3 (stone bricks) -&gt; {@code STONE_BRICK_STAIRS} and Tier 4
     * (bricks) -&gt; {@code BRICK_STAIRS} are exact texture matches for {@link TieredRoadwayBlock}'s
     * own per-tier look; Tier 5 (quartz bricks) has no vanilla stairs variant at all (confirmed
     * against the real block registry -- `quartz_bricks` never got one, unlike every other brick-
     * family block), so it's deliberately left paving flat steps exactly as before rather than
     * shipping a visually-mismatched substitute texture. Tiers 1-2 were never asked for and are
     * left untouched too (Tier 1's own path/dirt look has no stair concept either).
     */
    static BlockState stairStateFor(int tier, Direction facing) {
        net.minecraft.world.level.block.Block stairBlock = switch (tier) {
            case 3 -> Blocks.STONE_BRICK_STAIRS;
            case 4 -> Blocks.BRICK_STAIRS;
            default -> null;
        };
        if (stairBlock == null) {
            return null;
        }
        return stairBlock.defaultBlockState()
                .setValue(StairBlock.FACING, facing)
                .setValue(StairBlock.HALF, Half.BOTTOM);
    }

    /** Plain cardinal direction for an adjacent/diagonal centerline step -- picks the dominant axis if both are nonzero (a true diagonal step), preferring the larger delta. */
    private static Direction directionFromDelta(int dx, int dz) {
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static void paveColumn(ServerLevel level, int x, int targetY, int z, int naturalY, int tier, BlockState stepState, List<SnapshotEntry> snapshot, Set<BlockPos> captured) {
        BlockState roadState = stepState != null ? stepState : ModBlocks.ROADWAY.get().defaultBlockState().setValue(TieredRoadwayBlock.TIER, tier);
        if (targetY > naturalY) {
            // Causeway -- fill a solid support earthwork from natural terrain up to road level.
            for (int y = naturalY; y < targetY; y++) {
                captureAndSet(level, new BlockPos(x, y, z), roadState, snapshot, captured);
            }
        } else if (targetY < naturalY) {
            // Cut -- clear from just above road level up through the old natural surface, but never
            // higher than the same CLEARANCE_BLOCKS band every other column gets (fixed 2026-10-12,
            // real live report + diagram: "it still destroyed land above the road too high"). This
            // was the actual bug the whole time, not the clearance band below (which was always
            // computing correctly per column) -- when a road's pinned endpoint sits well below the
            // real natural surface at that exact spot (e.g. diving toward a cave/ravine), the
            // slope-limited profile has to dive away from a still-near-surface naturalY as fast as
            // the per-step clamp allows, so for a long stretch targetY ends up far below naturalY.
            // Uncapped, this carved a full vertical shaft from road level all the way up to the old
            // surface at every one of those columns -- tens of blocks tall at the deepest point,
            // tapering to nothing as the road approached where it actually meets natural grade. That
            // tapering wedge is exactly the triangular over-clearing shape reported. A deep cut now
            // reads as a proper tunnel (solid rock/terrain left overhead beyond the clearance band)
            // instead of an open-air gash down to the surface.
            int cutTop = Math.min(naturalY, targetY + CLEARANCE_BLOCKS);
            for (int y = targetY + 1; y <= cutTop; y++) {
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
