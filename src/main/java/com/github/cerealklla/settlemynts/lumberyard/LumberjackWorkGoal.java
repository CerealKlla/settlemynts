package com.github.cerealklla.settlemynts.lumberyard;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.github.cerealklla.cartographyr.geo.Geometry;
import com.github.cerealklla.settlemynts.api.Settlemynts;
import com.github.cerealklla.settlemynts.zone.ContainerDeposit;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * A Lumberyard worker's actual job (added 2026-10-05, explicit user request) -- one self-contained
 * state-machine {@link Goal} (this codebase's established "one goal class per behavior" convention,
 * not a generic engine), mirroring {@code resident.ResidentPatrolGoal}'s polygon-confined-movement
 * shape but adding a real arrive-then-act step. Harvesting takes priority over planting whenever both
 * are available.
 *
 * <p>Both the harvest-tree-base detection and the plant-spot/spacing checks are derived entirely from
 * live world state (no persisted "I planted this" memory) -- see {@code LumberjackWorkerEntity}'s own
 * class doc and this session's plan for why: a fresh worker respawning after death must still find
 * real work correctly with no memory of what the previous instance did.
 *
 * <p><b>"One lap per day" removed, 2026-10-06</b> (explicit user request: back to constantly working
 * -- the day-gate was observed getting workers stuck/stopped trying) -- {@code canUse} now just
 * keeps re-scanning for work on every reroll indefinitely, the same way it did before that gate
 * existed. {@code LumberjackWorkerEntity}'s {@code lastWorkDay}/{@code lapComplete} fields are gone
 * along with it.
 *
 * <p><b>Give-up timeout added, 2026-10-06</b> (real live bug: a worker was found permanently frozen
 * on Production, confirmed stationary for 20+ seconds via RCON while a different worker nearby kept
 * moving normally) -- root cause was this goal having no way to abandon an unreachable target: once
 * {@link #pendingTarget} was set, {@code canContinueToUse} returned {@code true} forever and {@code
 * tick} just kept re-issuing the same {@code moveTo} every 20 ticks indefinitely, with nothing ever
 * letting a fresh scan run. The user independently confirmed the live case was a trunk whose only
 * approach was blocked by its own leaves (motivating the "harvest breaks leaves too" change above,
 * already in place -- but that only helps once the worker *arrives*, not while it's still failing to
 * path there at all). {@code tick} now counts {@link #pursueTicks} while not yet arrived; past {@link
 * #MAX_PURSUE_TICKS} it gives up, remembers the target in {@link #recentlyAvoided} for {@link
 * #AVOID_DURATION_TICKS}, and clears {@code pendingTarget} so the next {@code canUse} scan is free to
 * pick something else (or the same tree again once the avoidance window expires, by which point the
 * leaves blocking it may well be gone from other activity). {@code findTarget} skips anything within
 * {@link #AVOID_RADIUS_BLOCKS} of a still-active avoided entry rather than exact-position matching,
 * since a wide (e.g. 2x2 dark oak) trunk resolves to a different base column per side.
 */
public class LumberjackWorkGoal extends Goal {

    private static final int REROLL_CHANCE_DENOMINATOR = 20;
    private static final double ARRIVE_DIST_SQ = 4.0;
    // Harvest-only. Was briefly widened to 225.0 (~15 blocks) on 2026-10-06 to work around a
    // teleport-onto-trunk edge case (LumberjackWorkerEntity#customServerAiStep's underground-escape
    // safety net could perch a worker high up a tall trunk, out of the old flat 2-block arrive
    // radius). Set to 4.0, 2026-10-11, then widened to 9.0 (3 blocks), 2026-10-12, then to 16.0
    // (4 blocks), 2026-10-21 (explicit user spec both times: "should consider getting within 3
    // blocks of the target as success"; later "once it gets within 4 blocks of a tree it can then
    // cut it down instead of insisting it touch the tree"). See pendingConnectedLogs's own doc for
    // the real structural fix alongside this widening -- a single fixed target point, however
    // generous the radius around it, was still the wrong thing to measure distance to.
    private static final double HARVEST_ARRIVE_DIST_SQ = 16.0;
    /** Bonus Sapling chance on tree harvest -- see {@link #harvest}'s own doc, 2026-10-28. */
    private static final float BONUS_SAPLING_CHANCE = 0.15F;
    // Randomized per scan (2026-10-05, explicit user request: "5-7 instead of flat 4") -- varies the
    // plot's planting pattern instead of a perfectly uniform grid.
    private static final int MIN_SAPLING_SPACING_BLOCKS = 5;
    private static final int MAX_SAPLING_SPACING_BLOCKS = 7;
    private static final int MAX_COLUMNS_SCANNED = 400; // bounded scan -- see class doc, not meant for huge polygons.
    private static final Set<Block> PLANTABLE_SOIL = Set.of(
            net.minecraft.world.level.block.Blocks.GRASS_BLOCK, net.minecraft.world.level.block.Blocks.DIRT,
            net.minecraft.world.level.block.Blocks.COARSE_DIRT, net.minecraft.world.level.block.Blocks.PODZOL,
            net.minecraft.world.level.block.Blocks.MYCELIUM, net.minecraft.world.level.block.Blocks.ROOTED_DIRT);

    private static final int MAX_PURSUE_TICKS = 100; // ~5s of real failure to arrive -- give up.
    private static final long AVOID_DURATION_TICKS = 1200; // 1 minute before retrying the same spot.
    private static final double AVOID_RADIUS_BLOCKS = 3.0;
    // How many consecutive give-ups in a row, with the worker's own position never actually moving,
    // before concluding pathfinding itself is broken here (not just an unlucky string of unreachable
    // targets) and teleporting straight to the target instead -- see tick's own give-up branch doc.
    private static final int STUCK_RESCUE_THRESHOLD = 3;
    private static final double STUCK_MOVEMENT_THRESHOLD_SQ = 9.0; // 3 blocks -- "didn't really go anywhere."

    private final LumberjackWorkerEntity worker;
    private final double speedModifier;

    private BlockPos pendingTarget;
    // The tree's own stable identity point (its ground-level base) for a harvest target -- null for a
    // plant target. Kept separate from pendingTarget (the worker's actual navigation goal, which may
    // be a different, more reachable log in the same connected tree -- see findTarget's own doc)
    // specifically so avoidance tracking stays keyed to the tree itself, not to wherever the worker
    // happened to be aiming for that attempt.
    private BlockPos pendingHarvestIdentity;
    // Real report, 2026-10-21: "Lumberyard NPC is again just not cutting down trees... I can't
    // belive how many iterations we are on and the NPCs are still having problems just walking
    // places." Every prior round (findTrunkBase, nearestConnectedLog, successive
    // HARVEST_ARRIVE_DIST_SQ widenings) kept trying to pick a SINGLE better fixed point to measure
    // arrival against -- but a worker's actual approach angle to a dense/leafy/branchy tree can
    // still end up closer to some OTHER member of the same connected structure than to whichever
    // one log was picked once, back when the goal started. The real fix: measure arrival against
    // the tree as a whole, not one arbitrarily-chosen log in it -- the full connected-log set
    // (already computed once in findTarget, reused here instead of discarded) lets tick() check
    // distance to the NEAREST log in the real structure every time, not a single stale point. Null
    // for a plant target (no tree to measure against).
    private Set<BlockPos> pendingConnectedLogs;
    private boolean pendingIsHarvest;
    private int repathCooldown;
    private int pursueTicks;
    private final List<AvoidedTarget> recentlyAvoided = new ArrayList<>();
    // Stuck-pathfinding rescue state -- see tick's own give-up branch doc.
    private BlockPos stuckGiveUpPos;
    private int consecutiveStuckGiveUps;

    // "private residence farmers and lumberjacks are only supposed to harvest and re-plant existing
    // plants, not plant wholly new ones" (explicit user spec, 2026-10-16) -- on a plot whose own Zone
    // Type isn't genuinely Lumberyard (a hired "Change Job" worker, e.g. on Private Residence), a
    // chopped tree's own base is remembered here (short-lived, in-memory only, same volatility class
    // as recentlyAvoided -- consistent with this class's existing "no persisted memory" design) so
    // findTarget can replant exactly where that tree stood without ever searching for brand-new bare
    // ground elsewhere in the plot. Unused (always null) on a native Lumberyard zone, which keeps its
    // existing broader "plant anywhere spaced out" behavior unchanged.
    private BlockPos lastHarvestedTreeBase;

    private record AvoidedTarget(BlockPos pos, long expiresAtGameTime) {
    }

    public LumberjackWorkGoal(LumberjackWorkerEntity worker, double speedModifier) {
        this.worker = worker;
        this.speedModifier = speedModifier;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    // Temporary instrumentation (2026-10-21) -- see diagnostics.TickProfiler's own doc.
    @Override
    public boolean canUse() {
        return com.github.cerealklla.settlemynts.diagnostics.TickProfiler.timeValue("LumberjackWorkGoal.canUse", this::canUseInternal);
    }

    private boolean canUseInternal() {
        if (!worker.getNavigation().isDone()) {
            return false;
        }
        if (worker.getRandom().nextInt(REROLL_CHANCE_DENOMINATOR) != 0) {
            return false;
        }
        if (!(worker.level() instanceof ServerLevel level)) {
            return false;
        }
        Optional<Geometry.Polygon> area = worker.resolvePatrolArea(level);
        if (area.isEmpty()) {
            return false;
        }
        WorkTarget found = findTarget(level, area.get());
        if (found == null) {
            return false;
        }
        pendingTarget = found.pos();
        pendingIsHarvest = found.isHarvest();
        pendingHarvestIdentity = found.identityBase();
        pendingConnectedLogs = found.connectedLogs();
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return pendingTarget != null;
    }

    @Override
    public void start() {
        repathCooldown = 0;
        pursueTicks = 0;
        if (pendingTarget != null) {
            worker.getNavigation().moveTo(pendingTarget.getX() + 0.5, pendingTarget.getY(), pendingTarget.getZ() + 0.5, speedModifier);
        }
    }

    @Override
    public void stop() {
        pendingTarget = null;
        pendingHarvestIdentity = null;
        pendingConnectedLogs = null;
    }

    // Temporary instrumentation (2026-10-21) -- see diagnostics.TickProfiler's own doc.
    @Override
    public void tick() {
        com.github.cerealklla.settlemynts.diagnostics.TickProfiler.time("LumberjackWorkGoal.tick", this::tickInternal);
    }

    private void tickInternal() {
        if (pendingTarget == null || !(worker.level() instanceof ServerLevel level)) {
            return;
        }
        double arriveDistSq = pendingIsHarvest ? HARVEST_ARRIVE_DIST_SQ : ARRIVE_DIST_SQ;
        // Measured against the nearest log in the whole connected tree, not just pendingTarget --
        // see pendingConnectedLogs's own field doc for why a single fixed point was never reliable.
        double currentDistSq = nearestDistSqr(worker.blockPosition(), pendingConnectedLogs, pendingTarget);
        if (currentDistSq > arriveDistSq) {
            if (++pursueTicks > MAX_PURSUE_TICKS) {
                // Keyed off the tree's stable identity (ground-level base), not pendingTarget itself --
                // see pendingHarvestIdentity's own doc. For a plant target there's no separate
                // identity, so pendingTarget is still the right (and only) key.
                BlockPos avoidKey = pendingIsHarvest && pendingHarvestIdentity != null ? pendingHarvestIdentity : pendingTarget;
                recentlyAvoided.add(new AvoidedTarget(avoidKey, level.getGameTime() + AVOID_DURATION_TICKS));
                // Real live report, 2026-10-21: a worker standing on genuinely normal, legitimate
                // ground (confirmed directly against the real terrain via RCON -- not elevated, not
                // trapped underground/in-canopy, both already covered by the entity's own safety
                // nets) still failed to reach ANY target, repeatedly, across several different
                // harvest/plant candidates in a row, never moving at all. This plot's own steep
                // terraced terrain (the same one the fall-damage/leaves fixes above were built for)
                // can apparently still defeat vanilla's pathfinder outright on some columns -- too
                // many stacked multi-block elevation changes for it to ever resolve a route, not a
                // reachability problem any amount of retrying or re-targeting fixes. Tracked via
                // {@link #stuckGiveUpPos}/{@link #consecutiveStuckGiveUps}: once several give-ups in
                // a row happen without the worker's own position moving at all, pathfinding itself is
                // the thing that's broken here, not the target choice -- teleport straight to the
                // real surface at the target's own column as a last resort, bypassing the pathfinder
                // entirely, the same "direct teleport" shape this entity's other safety nets already
                // use for an unrecoverable situation.
                BlockPos currentPos = worker.blockPosition();
                if (stuckGiveUpPos != null && currentPos.distSqr(stuckGiveUpPos) <= STUCK_MOVEMENT_THRESHOLD_SQ) {
                    consecutiveStuckGiveUps++;
                } else {
                    consecutiveStuckGiveUps = 1;
                }
                stuckGiveUpPos = currentPos;
                if (consecutiveStuckGiveUps >= STUCK_RESCUE_THRESHOLD) {
                    // 2026-10-22: used to teleport to a freshly-computed "real surface" at the tree's
                    // own column, which went through two real live-incident rounds of its own (landing
                    // workers inside a trunk/canopy, then inside the un-harvested tree itself) before
                    // ever being made reliable -- see this class's own history, now superseded. Every
                    // one of those bugs came from trying to compute a new safe landing spot on the fly;
                    // teleporting to the worker's own known-safe spawn point instead needs no
                    // computation (or tree-awareness) at all.
                    BlockPos rescuePos = worker.spawnPos();
                    worker.getNavigation().stop();
                    if (rescuePos != null) {
                        worker.teleportTo(rescuePos.getX() + 0.5, rescuePos.getY(), rescuePos.getZ() + 0.5);
                    }
                    consecutiveStuckGiveUps = 0;
                    stuckGiveUpPos = null;
                }
                pendingTarget = null;
                pendingHarvestIdentity = null;
                return;
            }
            if (worker.getNavigation().isDone() && repathCooldown-- <= 0) {
                worker.getNavigation().moveTo(pendingTarget.getX() + 0.5, pendingTarget.getY(), pendingTarget.getZ() + 0.5, speedModifier);
                repathCooldown = 20;
            }
            return;
        }
        // Real arrival (not a rescue teleport) -- proves pathfinding genuinely works from here, so
        // the stuck-streak tracked for the rescue fallback above shouldn't carry into an unrelated
        // future failure.
        consecutiveStuckGiveUps = 0;
        stuckGiveUpPos = null;
        if (pendingIsHarvest) {
            // Temporary instrumentation (2026-10-21) -- see diagnostics.TickProfiler's own doc. A
            // real harvest (flood-fill + leaf-bounding-box clear) only happens occasionally (once
            // per completed tree, not every tick) -- wrapped separately from the rest of tick() so a
            // periodic spike here isn't confused with the far-more-frequent arrival/pursuit path.
            com.github.cerealklla.settlemynts.diagnostics.TickProfiler.time(
                    "LumberjackWorkGoal.harvest", () -> harvest(level, pendingTarget));
            if (pendingHarvestIdentity != null) {
                lastHarvestedTreeBase = pendingHarvestIdentity;
            }
        } else {
            plant(level, pendingTarget);
            lastHarvestedTreeBase = null; // Used (or no longer valid) either way -- don't keep offering it as a candidate.
        }
        pendingTarget = null;
        pendingHarvestIdentity = null;
    }

    // identityBase/connectedLogs are only meaningful when isHarvest is true -- both null for a plant target.
    private record WorkTarget(BlockPos pos, boolean isHarvest, BlockPos identityBase, Set<BlockPos> connectedLogs) {
    }

    /** {@code from}'s squared distance to the nearest position in {@code logs}, or to {@code fallback} if {@code logs} is null/empty (a plant target, or a safety net). */
    private static double nearestDistSqr(BlockPos from, Set<BlockPos> logs, BlockPos fallback) {
        if (logs == null || logs.isEmpty()) {
            return from.distSqr(fallback);
        }
        double nearest = Double.MAX_VALUE;
        for (BlockPos pos : logs) {
            double distSq = from.distSqr(pos);
            if (distSq < nearest) {
                nearest = distSq;
            }
        }
        return nearest;
    }

    /** Prunes expired entries, then true if {@code pos} is within {@link #AVOID_RADIUS_BLOCKS} of a still-active one. */
    private boolean isAvoided(ServerLevel level, BlockPos pos) {
        long now = level.getGameTime();
        Iterator<AvoidedTarget> it = recentlyAvoided.iterator();
        boolean avoided = false;
        while (it.hasNext()) {
            AvoidedTarget avoidedTarget = it.next();
            if (avoidedTarget.expiresAtGameTime() <= now) {
                it.remove();
                continue;
            }
            if (avoidedTarget.pos().distSqr(pos) <= AVOID_RADIUS_BLOCKS * AVOID_RADIUS_BLOCKS) {
                avoided = true;
            }
        }
        return avoided;
    }

    // Precomputed once (not per findTarget call): every (dx, dz) offset within MAX_SCAN_RADIUS_BLOCKS,
    // sorted nearest-first -- see findTarget's own doc for why this replaced a fixed raster scan order.
    private static final int MAX_SCAN_RADIUS_BLOCKS = 80;
    private static final List<int[]> SPIRAL_OFFSETS = buildSpiralOffsets(MAX_SCAN_RADIUS_BLOCKS);

    private static List<int[]> buildSpiralOffsets(int maxRadius) {
        List<int[]> offsets = new ArrayList<>();
        for (int dx = -maxRadius; dx <= maxRadius; dx++) {
            for (int dz = -maxRadius; dz <= maxRadius; dz++) {
                offsets.add(new int[]{dx, dz});
            }
        }
        offsets.sort(java.util.Comparator.comparingInt(o -> o[0] * o[0] + o[1] * o[1]));
        return offsets;
    }

    /**
     * Scans outward from the worker's OWN current position (nearest-first, via {@link
     * #SPIRAL_OFFSETS}) instead of a fixed top-left-to-bottom-right raster order -- real production
     * incident, 2026-10-21: temporary diagnostic logging (added to chase a live "stands next to a
     * tree doing nothing despite plenty of harvestable trees nearby" report) showed the exact same
     * couple of far-corner trees being picked as the target over and over, by entirely different
     * worker instances, every one of them giving up after the full 100-tick pursuit timeout having
     * barely moved from its own spawn point. The raster scan always returns the first qualifying
     * candidate in a fixed (minX, minZ)-first order regardless of where the worker actually is --
     * on a large plot, that's very often a tree 30+ blocks away while a much closer one sits
     * unconsidered. Chasing a far tree across this plot's own steep terraced terrain (visible in the
     * reporting screenshot) for the full pursuit window, repeatedly, is also a very plausible
     * explanation for the worker population churning through several different UUIDs in the same
     * short window -- fall damage on the terraces was never ruled out, so {@code
     * LumberjackWorkerEntity} also gained fall-damage immunity the same day, as a direct, independent
     * safety net regardless of how well this fix reduces unnecessary travel.
     *
     * <p>Deliberately still an early-exit scan (stops at the first qualifying candidate, same as
     * before) rather than evaluating every column and comparing -- {@link #findTrunkBase} already
     * flood-fills up to {@link #MAX_CONNECTED_LOGS} blocks the instant it sees a log-topped column, so
     * evaluating many candidates per call (instead of stopping at the first) would multiply that cost
     * by however many trees exist in the plot. Reordering the scan is the cheap fix; "first found"
     * under this new order already means "nearest," with no extra flood-fill work needed.
     */
    private WorkTarget findTarget(ServerLevel level, Geometry.Polygon area) {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Geometry.Polygon.Vertex v : area.vertices()) {
            minX = Math.min(minX, v.x());
            minZ = Math.min(minZ, v.z());
            maxX = Math.max(maxX, v.x());
            maxZ = Math.max(maxZ, v.z());
        }

        int spacing = MIN_SAPLING_SPACING_BLOCKS + worker.getRandom().nextInt(MAX_SAPLING_SPACING_BLOCKS - MIN_SAPLING_SPACING_BLOCKS + 1);
        boolean nativeZone = worker.isNativeLumberyardZone(level);
        int scanned = 0;
        BlockPos firstPlantCandidate = null;
        BlockPos workerPos = worker.blockPosition();
        int originX = workerPos.getX();
        int originZ = workerPos.getZ();
        for (int[] offset : SPIRAL_OFFSETS) {
            int x = originX + offset[0];
            int z = originZ + offset[1];
            if (x < minX || x > maxX || z < minZ || z > maxZ || !area.contains(x, z)) {
                continue;
            }
            if (scanned++ > MAX_COLUMNS_SCANNED) {
                return firstPlantCandidate == null ? null : new WorkTarget(firstPlantCandidate, false, null, null);
            }
            int topY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos topSolid = new BlockPos(x, topY - 1, z);
            BlockState topState = level.getBlockState(topSolid);
            if (topState.is(BlockTags.LOGS)) {
                // Computed once and reused for both the stable base identity and the nearest-
                // approach point below (findTrunkBase/nearestConnectedLog used to each flood-fill
                // this same connected structure independently) -- also now carried forward as
                // pendingConnectedLogs, so tick()'s own arrival check can measure against the real
                // tree instead of one fixed point (see that field's own doc).
                Set<BlockPos> connected = findConnectedLogs(level, topSolid);
                BlockPos base = lowestOf(connected, topSolid);
                if (isRealTree(level, base) && !isAvoided(level, base)) {
                    // Real live report, 2026-10-12: "Lumberjacks are very clearly targeting the
                    // tops of trees... they just climb to the top and then stand there instead of
                    // chopping." The ground-level base is still this method's stable identity for
                    // isRealTree/avoidance tracking (same log, no matter which direction the
                    // worker approaches from) -- but a wide-trunked or buttressed tree (dark oak
                    // 2x2, jungle) can have every ground-level neighbor of the base ALSO be solid
                    // trunk, making the base itself physically unreachable within
                    // HARVEST_ARRIVE_DIST_SQ from any ground path. The worker's pathfinder then
                    // has nothing better to do than climb the trunk's exterior looking for
                    // somewhere within range, and gets stuck there, repeatedly failing to arrive.
                    // Since harvest() already flood-fills the whole connected tree from wherever
                    // the worker actually ends up, the real fix is to aim it at whichever log in
                    // that same connected structure is nearest the worker's OWN current position
                    // -- almost always something near the worker's own height, genuinely
                    // reachable on foot, not the base specifically.
                    BlockPos approach = nearestOf(connected, workerPos, base);
                    return new WorkTarget(approach, true, base, connected); // Harvest always wins immediately once found.
                }
                // Not a real tree (no leaves anywhere nearby) -- almost certainly a player-built
                // log structure (2026-10-05, real report: "destroyed the building to try to plant
                // trees" -- this is the actual fix, see isRealTree's own doc). Skip it entirely,
                // don't treat it as a planting spot either.
                continue;
            }
            if (firstPlantCandidate == null && PLANTABLE_SOIL.contains(topState.getBlock())
                    && level.getBlockState(topSolid.above()).canBeReplaced()
                    && !hasNearbyTreeOrSapling(level, area, x, topSolid.getY(), z, spacing)
                    && !isAvoided(level, topSolid.above())
                    // On a non-native Zone Type, only ever replant exactly where this same worker
                    // just chopped a real tree -- never expand into brand-new bare ground. See
                    // lastHarvestedTreeBase's own field doc.
                    && (nativeZone || topSolid.above().equals(lastHarvestedTreeBase))) {
                firstPlantCandidate = topSolid.above();
            }
        }
        return firstPlantCandidate == null ? null : new WorkTarget(firstPlantCandidate, false, null, null);
    }

    /**
     * Resolves whatever log block the heightmap scan actually found down to the tree's true
     * ground-level base -- real report, 2026-10-11: "not clearing trees when he gets within 4 blocks
     * of the base of the tree, it seems like he is trying to get to the very top of the tree." Root
     * cause: this used to just walk straight down the *same x,z column* looking for more logs, but
     * the column {@code findTarget}'s heightmap scan hits isn't always the trunk's own column -- it
     * can land on a branch log sitting out over the canopy, which (for most vanilla tree shapes)
     * isn't directly above another log at all, just leaves. Walking straight down from a branch
     * stopped immediately, handing back that elevated branch position as "the base" -- so the worker
     * was correctly arriving at the real ground-level trunk but being told to reach a target still
     * floating up in the canopy, which it could never get within harvest range of.
     *
     * <p>Fixed by flood-filling the whole connected log structure first (same {@link
     * #findConnectedLogs} harvest itself already uses -- which member log it's started from doesn't
     * matter, connectivity finds the same full set either way) and taking its lowest log as the real
     * base, rather than only ever looking straight down from one specific column. Takes the already-
     * computed connected set directly (2026-10-21) rather than re-flood-filling it a second time --
     * see this method's own call site in {@code findTarget}.
     */
    private static BlockPos lowestOf(Set<BlockPos> connectedLogs, BlockPos anyLog) {
        BlockPos lowest = anyLog;
        for (BlockPos pos : connectedLogs) {
            if (pos.getY() < lowest.getY()) {
                lowest = pos;
            }
        }
        return lowest;
    }

    /**
     * Whichever log in {@code connectedLogs} sits closest to {@code workerPos} -- see this method's
     * own call site for why the navigation target and the stable tree-identity point ({@code base})
     * are deliberately kept separate now. Always includes {@code base} itself in the search, so a
     * tree the worker can genuinely reach at ground level behaves exactly as before.
     */
    private static BlockPos nearestOf(Set<BlockPos> connectedLogs, BlockPos workerPos, BlockPos base) {
        BlockPos nearest = base;
        double nearestDistSq = workerPos.distSqr(base);
        for (BlockPos pos : connectedLogs) {
            double distSq = workerPos.distSqr(pos);
            if (distSq < nearestDistSq) {
                nearest = pos;
                nearestDistSq = distSq;
            }
        }
        return nearest;
    }

    /**
     * Checks a direct vertical band around the candidate's own ground level ({@code baseY}) for an
     * existing sapling or tree in every neighboring column within the spacing radius -- deliberately
     * NOT heightmap-based (fixed 2026-10-05, real report: "planting trees right next to each other,
     * not spaced 4 away"). A {@code SaplingBlock} has no collision, so {@code
     * Heightmap.Types.MOTION_BLOCKING_NO_LEAVES} treats it as transparent and reports the *soil*
     * underneath as the column's top instead -- the original heightmap-based check could therefore
     * never actually see a freshly-planted, not-yet-grown sapling at all, only a fully-grown tree's
     * logs (which genuinely are solid). Scanning a fixed band directly (ground level up through a
     * young tree's own height) catches both cases reliably, assuming the plot's own terrain is
     * reasonably flat near the candidate -- same approximation this whole feature already makes.
     */
    private static boolean hasNearbyTreeOrSapling(ServerLevel level, Geometry.Polygon area, int x, int baseY, int z, int spacing) {
        for (int dx = -spacing; dx <= spacing; dx++) {
            for (int dz = -spacing; dz <= spacing; dz++) {
                if (dx == 0 && dz == 0 || dx * dx + dz * dz > spacing * spacing) {
                    continue;
                }
                int nx = x + dx;
                int nz = z + dz;
                if (!area.contains(nx, nz)) {
                    continue;
                }
                for (int dy = -1; dy <= 6; dy++) {
                    BlockState state = level.getBlockState(new BlockPos(nx, baseY + dy, nz));
                    if (state.is(BlockTags.LOGS) || state.is(BlockTags.SAPLINGS)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // How far a tree's own branches/canopy can plausibly extend from its trunk column -- bounds
    // both isRealTree's leaf search and harvest's own break region. Deliberately small and
    // *position*-bounded, not connectivity-bounded -- see both methods' own doc for why.
    private static final int CANOPY_HORIZONTAL_RADIUS = 2;
    private static final int CANOPY_VERTICAL_MARGIN = 3;
    private static final int MAX_TRUNK_HEIGHT = 12; // generously covers every vanilla tree's trunk.

    /** Walks straight up through a vertical log column to find where the trunk ends. */
    private static BlockPos trunkTop(ServerLevel level, BlockPos base) {
        BlockPos pos = base;
        int climbed = 0;
        while (climbed < MAX_TRUNK_HEIGHT && level.getBlockState(pos.above()).is(BlockTags.LOGS)) {
            pos = pos.above();
            climbed++;
        }
        return pos;
    }

    /**
     * Checks whether a discovered trunk is a real, naturally-grown tree (has leaves somewhere in its
     * own small canopy area) rather than a player-built log structure -- fixed 2026-10-05, real
     * report: "destroyed the building to try to plant trees." A log cabin wall is still just {@code
     * BlockTags.LOGS} blocks, indistinguishable from a trunk by block type alone; real vanilla trees
     * always generate with leaves attached, buildings essentially never do.
     *
     * <p><b>Deliberately a fixed local bounding box around the trunk column, not a connectivity-based
     * flood-fill</b> -- a flood-fill following every connected log would also follow straight from a
     * real tree's trunk into an adjacent building's log wall if the two happen to touch, validating
     * (and, worse, later harvesting -- see {@link #harvest}) the *entire* connected mass as one "real
     * tree" the instant it found a single leaf anywhere in that merged cluster. A small box anchored
     * to the trunk's own position can never reach that far regardless of what's connected to what.
     */
    private static boolean isRealTree(ServerLevel level, BlockPos base) {
        BlockPos top = trunkTop(level, base);
        for (int x = base.getX() - CANOPY_HORIZONTAL_RADIUS; x <= base.getX() + CANOPY_HORIZONTAL_RADIUS; x++) {
            for (int z = base.getZ() - CANOPY_HORIZONTAL_RADIUS; z <= base.getZ() + CANOPY_HORIZONTAL_RADIUS; z++) {
                for (int y = base.getY(); y <= top.getY() + CANOPY_VERTICAL_MARGIN; y++) {
                    if (level.getBlockState(new BlockPos(x, y, z)).is(BlockTags.LEAVES)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void plant(ServerLevel level, BlockPos pos) {
        // Live re-check (2026-10-05, real report: NPCs destroying non-soil/solid blocks while
        // planting) -- never overwrite anything that isn't genuinely clearable (grass, tall grass,
        // snow layers, etc. are all fine per explicit user instruction; a real solid block is not),
        // and re-verify the ground below is still actual plantable soil, not just whatever the scan
        // saw some ticks ago.
        if (!level.getBlockState(pos).canBeReplaced()) {
            return;
        }
        if (!PLANTABLE_SOIL.contains(level.getBlockState(pos.below()).getBlock())) {
            return;
        }
        Item sapling = chooseSaplingToPlant(level, pos);
        Block saplingBlock = Block.byItem(sapling);
        if (saplingBlock != net.minecraft.world.level.block.Blocks.AIR) {
            level.setBlock(pos, saplingBlock.defaultBlockState(), 3);
        }
    }

    /** Random pick among this plot's configured Sapling slots (see {@link com.github.cerealklla.settlemynts.zone.WorkerPlantSelection}), falling back to the existing single biome-based pick if nothing's configured -- same shape as {@code farm.FarmerWorkGoal#chooseCropToPlant}, Plant Selection, 2026-10-28. */
    private Item chooseSaplingToPlant(ServerLevel level, BlockPos pos) {
        Item fallback = LumberjackSaplingSpecies.localSpeciesSapling(level, pos);
        Optional<com.github.cerealklla.settlemynts.zone.PlotRecord> plot = Settlemynts.findPlotRecord(level, worker.plotId());
        if (plot.isEmpty()) {
            return fallback;
        }
        return com.github.cerealklla.settlemynts.zone.WorkerPlantSelection.chooseRandom(
                plot.get().plantSelections(), plot.get().tier(), level.getRandom(), fallback);
    }

    // Bounds the log flood-fill's worst case, mirroring Lyfe's own Lumberjack-skill cap exactly
    // (gathering.WholeStructureClear#MAX_BLOCKS, raised 64 -> 256 the same day for the same real
    // report: "insta-chop for trees needs to clear even higher; i have some huge oaks and it's
    // leaving the very tops" -- a large/fancy 2x2-trunk oak's own logs can genuinely exceed 64, and
    // since this is a breadth-first fill, hitting the cap leaves out whichever blocks are farthest
    // from the start first, which for a tall tree is the top). Same accepted tradeoff that cap
    // already makes: a flood-fill could in principle walk from a real tree's trunk into an adjacent
    // player-built log structure if the two happen to touch, but isRealTree already gates entry (a
    // cabin wall alone never qualifies), and this cap bounds how far into anything connected the
    // clear can ever reach regardless.
    private static final int MAX_CONNECTED_LOGS = 256;

    /**
     * Flood-fills every log connected to {@code base} (26-neighbor, matching {@code BlockTags.LOGS})
     * instead of the old fixed local box -- added 2026-10-09, real report (screenshot): wide/branchy
     * trees were left with floating logs after a harvest, since the previous box was sized around just
     * the base/top trunk column and a real tree's branches can extend well outside that. This is the
     * exact same algorithm the Lumberjack *skill* already uses for a player's own tree-chop ({@code
     * lyfe.gathering.WholeStructureClear#connectedBlocksOf}, including its 26-neighbor offsets for
     * diagonally-offset branch logs and its same safety cap) -- duplicated here in full rather than
     * taken as a dependency, since Settlemynts' worker AI must keep working correctly on a server with
     * no Lyfe installed at all.
     */
    private static Set<BlockPos> findConnectedLogs(ServerLevel level, BlockPos base) {
        Set<BlockPos> found = new java.util.HashSet<>();
        java.util.Deque<BlockPos> frontier = new java.util.ArrayDeque<>();
        frontier.add(base);
        found.add(base);
        while (!frontier.isEmpty() && found.size() < MAX_CONNECTED_LOGS) {
            BlockPos current = frontier.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos neighbor = current.offset(dx, dy, dz);
                        if (found.contains(neighbor)) {
                            continue;
                        }
                        if (level.getBlockState(neighbor).is(BlockTags.LOGS)) {
                            found.add(neighbor);
                            frontier.add(neighbor);
                            if (found.size() >= MAX_CONNECTED_LOGS) {
                                break;
                            }
                        }
                    }
                }
            }
        }
        return found;
    }

    /**
     * Breaks every connected log (flood-filled, see {@link #findConnectedLogs}) plus every leaf within
     * a margin of the real discovered trunk/branch footprint -- reworked 2026-10-09 from the old fixed
     * local box (see {@link #findConnectedLogs}'s own doc for why). The leaf box is now the bounding
     * box of every log actually found, expanded by {@link #CANOPY_HORIZONTAL_RADIUS}/{@link
     * #CANOPY_VERTICAL_MARGIN}, instead of a box anchored only at the trunk's own base/top column --
     * the same floating-canopy bug would otherwise persist for leaves even after the logs themselves
     * were fully cleared.
     *
     * <p>Leaves now roll their real loot table (explicit user request, 2026-10-09: "pick up anything
     * dropped by the tree... I would expect to see Apples and stick too (just not saplings)") via
     * {@link Block#getDrops}, the same real-loot-table call {@code construction.SiteTerrainOps} already
     * uses elsewhere in the suite for an identical "no player/tool context" case -- this naturally
     * covers vanilla's own stick drop chance (added to every leaf type) and Oak's extra apple chance,
     * with no hand-authored drop table needed. Sapling drops are deliberately filtered out and
     * discarded (not deposited, not even dropped as a loose item) per that same explicit instruction --
     * the Lumberjack already plants fresh saplings itself via {@link #plant}, so handing back the ones
     * it just broke would be redundant at best.
     */
    private void harvest(ServerLevel level, BlockPos base) {
        Set<BlockPos> logs = findConnectedLogs(level, base);
        int minX = base.getX(), maxX = base.getX();
        int minY = base.getY(), maxY = base.getY();
        int minZ = base.getZ(), maxZ = base.getZ();
        List<Container> boxes = null;
        for (BlockPos pos : logs) {
            minX = Math.min(minX, pos.getX());
            maxX = Math.max(maxX, pos.getX());
            minY = Math.min(minY, pos.getY());
            maxY = Math.max(maxY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxZ = Math.max(maxZ, pos.getZ());
            Item logItem = level.getBlockState(pos).getBlock().asItem();
            level.removeBlock(pos, false);
            if (logItem != net.minecraft.world.item.Items.AIR) {
                if (boxes == null) {
                    boxes = Settlemynts.resolvePlotBoxes(level, worker.plotId());
                }
                if (!boxes.isEmpty()) {
                    ContainerDeposit.depositIntoAny(boxes, new ItemStack(logItem, 1));
                }
            }
        }

        for (int x = minX - CANOPY_HORIZONTAL_RADIUS; x <= maxX + CANOPY_HORIZONTAL_RADIUS; x++) {
            for (int z = minZ - CANOPY_HORIZONTAL_RADIUS; z <= maxZ + CANOPY_HORIZONTAL_RADIUS; z++) {
                for (int y = minY; y <= maxY + CANOPY_VERTICAL_MARGIN; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState leafState = level.getBlockState(pos);
                    if (!leafState.is(BlockTags.LEAVES)) {
                        continue;
                    }
                    List<ItemStack> drops = Block.getDrops(leafState, level, pos, null);
                    level.removeBlock(pos, false);
                    for (ItemStack drop : drops) {
                        if (drop.isEmpty() || drop.is(net.minecraft.tags.ItemTags.SAPLINGS)) {
                            continue;
                        }
                        if (boxes == null) {
                            boxes = Settlemynts.resolvePlotBoxes(level, worker.plotId());
                        }
                        if (!boxes.isEmpty()) {
                            ContainerDeposit.depositIntoAny(boxes, drop);
                        }
                    }
                }
            }
        }

        // Bonus Sapling chance, 2026-10-28 (explicit user spec): "Every Tree or Plant destroyed by
        // an NPC should give a chance to retrieve an additional sapling... which can then enter the
        // town's economy." Rolled once per whole tree harvested (not per leaf block -- a real
        // vanilla sapling drop is already deliberately discarded above, per that doc's own earlier
        // explicit instruction; this is a separate, explicit bonus roll, same flat untuned rate
        // {@code farm.FarmerWorkGoal#BONUS_SEED_CHANCE} uses). Uses the tree's own local-species
        // sapling (same pick #plant would make here), not this plot's currently-configured slots --
        // a bonus from a harvested tree should match what was actually growing, not a random
        // reconfigured choice.
        if (!logs.isEmpty() && level.getRandom().nextFloat() < BONUS_SAPLING_CHANCE) {
            Item sapling = LumberjackSaplingSpecies.localSpeciesSapling(level, base);
            if (boxes == null) {
                boxes = Settlemynts.resolvePlotBoxes(level, worker.plotId());
            }
            if (!boxes.isEmpty()) {
                ContainerDeposit.depositIntoAny(boxes, new ItemStack(sapling, 1));
            }
        }
    }
}
