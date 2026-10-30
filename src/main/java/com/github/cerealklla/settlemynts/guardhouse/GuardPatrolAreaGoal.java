package com.github.cerealklla.settlemynts.guardhouse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import com.github.cerealklla.cartographyr.geo.Geometry;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.roadway.RoadwayStakeEntity;
import com.github.cerealklla.settlemynts.zone.PlotGeometry;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * Patrols the settlement's own road network (added 2026-10-06, explicit user request: "I'd like
 * the guards to walk along the roads when patrolling the settlement"). {@link #pickRecencyWeightedTarget}
 * walks the WHOLE settlement's {@link RoadwayStakeEntity} graph (not just this guard's own plot)
 * and picks a final destination weighted toward whichever eligible stake has gone longest without a
 * guard actually reaching it, then {@link #findStakePath} finds the real route there along the
 * graph's own connections. Each hop is issued as its own short {@code moveTo} call in turn (see
 * {@link #canContinueToUse}, which advances {@link #waypointIndex} once a hop is reached) -- so the
 * guard's actual walked route visibly follows stake to connected stake, matching how the road was
 * physically built. Falls back to a single direct hop if the destination stake is disconnected from
 * the guard's entry point (a malformed or still-being-built network), and to the original
 * plot-wander behavior if no roads/stakes exist at all for this settlement.
 *
 * <p><b>Reworked 2026-10-18 -- real observation, not a performance concern: "big portions of
 * sprawling towns virtually never patrolled."</b> The previous version (picking a uniformly random
 * stake past a flat 50-block distance floor) gave no guarantee every stake ever actually got a
 * turn -- a large network could keep re-rolling the same handful of "far enough" stakes near the
 * guard's own plot forever while an equally-valid stake on the far side of the settlement never
 * came up. Replaced with a real per-stake "last visited" clock ({@link
 * RoadwayStakeEntity#lastVisitedGameTime}/{@link RoadwayStakeEntity#markVisited}, stamped on
 * arrival at BOTH the final destination and every intermediate hop passed through along the way --
 * {@link #canContinueToUse} calls it the moment each waypoint is actually reached, not on a timer)
 * and a rank-based decay weighting over every eligible stake (oldest-visited first, see {@link
 * #pickRecencyWeightedTarget}) -- so coverage rotates through the WHOLE graph over repeated cycles
 * instead of only ever favoring distance. The old flat distance-floor and its closest-to-floor
 * fallback pool are gone entirely -- recency alone is now the only selection signal, and every
 * eligible stake (including nearby ones) is always a valid candidate, never excluded by distance.
 *
 * <p><b>Reservation against herding, same day</b> -- real question raised before building this:
 * multiple guards in one settlement independently weighting toward the same stale stake would just
 * pile onto it together. The chosen final destination is {@link RoadwayStakeEntity#claim claimed}
 * by this guard the instant it's picked (excluded from other guards' own selection pool via {@link
 * RoadwayStakeEntity#isClaimed} until released), and released the instant this guard actually
 * arrives there (see {@link #canContinueToUse}) or dies en route (see {@code GuardEntity#die}).
 * {@code RoadwayStakeEntity}'s own claim TTL backs a self-healing expiry in case neither ever runs
 * -- e.g. the stake's own chunk isn't loaded at the exact moment of death. A claim deliberately
 * does NOT stamp
 * {@code lastVisitedGameTime} -- only a genuine arrival does, so a guard dying en route never
 * falsely marks a stake as patrolled.
 *
 * <p>Only a stake {@link PlotGeometry#isWithinAnyTownProper} says is inside AT LEAST ONE of the
 * settlement's plots' own "Town Proper" buffers is eligible as a final DESTINATION -- per the
 * original spec: "you can't make a road go all the way out to the edge of a settlement and a guard
 * will just go there, they only protect buildings people [live in] that they can walk to within the
 * town itself." Intermediate hops along the way are not filtered by this -- the path just follows
 * whatever route the graph actually has between the entry point and the eligible destination.
 *
 * <p>If no roads exist yet for this settlement (or no stake is currently eligible -- e.g. every
 * stake is presently claimed by some other guard), falls back to the original v1 behavior: a
 * random point inside the guard's own plot's "Town Proper" buffer (see {@link
 * GuardEntity#resolvePatrolArea}), so a guard isn't simply frozen.
 *
 * <p><b>Correction</b> (unchanged from the original plot-wander behavior): if the guard is
 * currently outside its own plot's polygon when the fallback path is used, the next pick is
 * weighted toward that polygon's own bounding-box center instead of a uniformly random point.
 *
 * <p>Every picked target is resolved **at the real surface height** ({@link
 * Heightmap.Types#MOTION_BLOCKING_NO_LEAVES}) -- a guard never intentionally picks an underground
 * target. (A guard can still end up underground reactively, e.g. chasing a monster into a hole --
 * {@link GuardEntity#customServerAiStep} is the actual safety net for that, independent of this
 * goal.)
 */
public class GuardPatrolAreaGoal extends Goal {

    private static final int MAX_SAMPLE_ATTEMPTS = 10;

    // Rank-based decay for recency-weighted target selection -- rank 0 (the single oldest-visited
    // eligible stake) gets weight 1.0, rank 1 gets 0.5, rank 2 gets 0.25, and so on, normalized into
    // a probability distribution. Smoothly favors staleness across the WHOLE ranked list rather than
    // a hard cutoff (explicit correction from an earlier "60% to the single oldest, the rest split
    // evenly" proposal -- "I mean to have a decay curve, not only the oldest is 60% with the rest
    // even"). One tunable constant; retune after watching real patrol coverage play out live.
    private static final double RECENCY_WEIGHT_DECAY = 0.5;

    private final GuardEntity guard;
    private final double speedModifier;
    // The current committed hop target -- non-null for as long as this goal should keep running (see
    // canContinueToUse), cleared in stop() so a fresh pick happens next time (whether because the
    // whole route finished, or because a higher-priority goal like combat interrupted it).
    private BlockPos nextFinalDestination;
    // The full ordered sequence of hops (stake-to-connected-stake) from the entry point to the final
    // destination, resolved once in start() -- empty for the single-hop plot-wander fallback. Kept
    // as real stake references (not just positions) so arrival can stamp each one's own
    // lastVisitedGameTime, not merely drive toward a bare coordinate.
    private List<RoadwayStakeEntity> currentRoute = List.of();
    private int waypointIndex;

    public GuardPatrolAreaGoal(GuardEntity guard, double speedModifier) {
        this.guard = guard;
        this.speedModifier = speedModifier;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        return guard.getNavigation().isDone() && guard.level() instanceof ServerLevel;
    }

    // "Reached" a waypoint -- checked explicitly rather than relying solely on vanilla's own
    // Navigation#isDone(), whose node-reach radius (derived from the guard's bounding box) left
    // guards stopping noticeably short of the actual stake. 1 block (tightened from 2, 2026-10-09 --
    // "they still don't seem to arrive close enough to the actual road vertex").
    private static final double ARRIVE_DIST_SQ = 1.0;

    // Temporary instrumentation (2026-10-21) -- see diagnostics.TickProfiler's own doc. This is the
    // real "runs every tick while active" hook for this goal (it has no tick() override at all).
    @Override
    public boolean canContinueToUse() {
        return com.github.cerealklla.settlemynts.diagnostics.TickProfiler.timeValue(
                "GuardPatrolAreaGoal.canContinueToUse", this::canContinueToUseInternal);
    }

    private boolean canContinueToUseInternal() {
        if (nextFinalDestination == null) {
            return false;
        }
        double dx = guard.getX() - (nextFinalDestination.getX() + 0.5);
        double dz = guard.getZ() - (nextFinalDestination.getZ() + 0.5);
        boolean withinArriveDistance = (dx * dx + dz * dz) <= ARRIVE_DIST_SQ;
        if (!withinArriveDistance) {
            if (!guard.getNavigation().isDone()) {
                return true; // still actively en route
            }
            // Real bug fix, 2026-10-29 -- explicit user spec: "it should never say it succeeded
            // when it didn't... I'm fine with it marking it as failed and then re-assigning to the
            // new oldest stake." navigation.isDone() means the pathfinder gave UP (stuck/blocked/no
            // route), not that the guard actually reached this waypoint -- the old code treated
            // those as the same thing (an OR), so a guard that got stuck anywhere along the way was
            // silently marked as having arrived right where it gave up, with no retry. Now a failed
            // leg releases the claim and ends the goal without ever stamping markVisited, so the
            // next canUse()/start() cycle genuinely re-rolls through the same recency-weighted pool.
            releaseClaim();
            return false;
        }
        if (guard.level() instanceof ServerLevel level) {
            // Re-resolved by UUID rather than trusting the stored reference -- the stake's chunk may
            // have unloaded and reloaded (a fresh deserialized instance) sometime during this
            // possibly-long walk, and a write to a stale detached reference would silently never
            // persist.
            UUID reachedId = currentRoute.get(waypointIndex).getUUID();
            if (level.getEntity(reachedId) instanceof RoadwayStakeEntity reached) {
                reached.markVisited(level.getGameTime());
            }
        }
        if (waypointIndex + 1 < currentRoute.size()) {
            waypointIndex++;
            RoadwayStakeEntity next = currentRoute.get(waypointIndex);
            nextFinalDestination = stakeSurfacePos(next);
            guard.getNavigation().moveTo(
                    nextFinalDestination.getX() + 0.5, nextFinalDestination.getY(), nextFinalDestination.getZ() + 0.5, speedModifier);
            return true;
        }
        releaseClaim(); // final destination just reached -- see start()'s own claim
        return false; // whole route (or single-hop fallback) complete
    }

    @Override
    public void start() {
        if (!(guard.level() instanceof ServerLevel level)) {
            return;
        }
        // Temporary diagnostic (2026-10-10, see diagnostics.LagDiagnostics' own doc) -- remove once
        // the real lag cause is confirmed.
        long diagStart = System.nanoTime();
        List<RoadwayStakeEntity> route = buildRoadRoute(level);
        long diagElapsedMs = (System.nanoTime() - diagStart) / 1_000_000L;
        if (diagElapsedMs >= 5) {
            com.github.cerealklla.settlemynts.SettlemyntsMod.LOGGER.info("[LagDiagnostics] GuardPatrolAreaGoal.buildRoadRoute took {}ms", diagElapsedMs);
        }
        BlockPos fallbackTarget = null;
        if (route.isEmpty()) {
            fallbackTarget = guard.resolvePatrolArea(level).map(area -> pickTarget(level, area)).orElse(null);
        }
        currentRoute = route;
        waypointIndex = 0;
        if (!route.isEmpty()) {
            guard.setClaimedPatrolStakeId(route.get(route.size() - 1).getUUID());
            nextFinalDestination = stakeSurfacePos(route.get(0));
        } else {
            nextFinalDestination = fallbackTarget;
        }
        if (nextFinalDestination != null) {
            guard.getNavigation().moveTo(
                    nextFinalDestination.getX() + 0.5, nextFinalDestination.getY(), nextFinalDestination.getZ() + 0.5, speedModifier);
        }
    }

    @Override
    public void stop() {
        // Interrupted mid-route (e.g. combat) -- release the final-destination claim rather than
        // leaving it reserved until its TTL expires; a genuine arrival already released it itself in
        // canContinueToUse before this runs.
        releaseClaim();
        nextFinalDestination = null;
        currentRoute = List.of();
        waypointIndex = 0;
    }

    private void releaseClaim() {
        if (!currentRoute.isEmpty() && guard.level() instanceof ServerLevel level) {
            RoadwayStakeEntity destination = currentRoute.get(currentRoute.size() - 1);
            if (level.getEntity(destination.getUUID()) instanceof RoadwayStakeEntity resolved) {
                resolved.clearClaim();
            }
        }
        guard.setClaimedPatrolStakeId(null);
    }

    private static BlockPos stakeSurfacePos(RoadwayStakeEntity stake) {
        return new BlockPos(Mth.floor(stake.getX()), Mth.floor(stake.getY()), Mth.floor(stake.getZ()));
    }

    /**
     * Picks a recency-weighted eligible destination stake, finds the nearest stake to the guard's
     * current position as the entry point onto the road graph, and returns the real BFS hop path
     * between them (the entry point itself excluded, since the guard is already there) -- empty if
     * this settlement has no roads/stakes at all, or no eligible destination exists right now
     * (including "every eligible stake is currently claimed by another guard").
     */
    private List<RoadwayStakeEntity> buildRoadRoute(ServerLevel level) {
        UUID settlementCoreId = guard.getSettlementCoreId();
        if (settlementCoreId == null || !(level.getEntity(settlementCoreId) instanceof GhostTownHallCoreEntity core)) {
            return List.of();
        }
        List<RoadwayStakeEntity> stakes = RoadwayStakeEntity.findByOwnerCore(level, settlementCoreId);
        if (stakes.isEmpty()) {
            return List.of();
        }
        RoadwayStakeEntity destination = pickRecencyWeightedTarget(level, core, stakes);
        if (destination == null) {
            return List.of();
        }
        RoadwayStakeEntity entry = nearestStake(stakes);
        if (entry == null) {
            return List.of();
        }
        List<RoadwayStakeEntity> path = findStakePath(stakes, entry, destination);
        List<RoadwayStakeEntity> hops = path.size() >= 2 ? path.subList(1, path.size()) : List.of(destination);
        destination.claim(guard.getUUID(), level.getGameTime());
        return hops;
    }

    /**
     * Ranks every eligible, currently-unclaimed stake oldest-visited-first and picks one with
     * rank-based decay weighting ({@link #RECENCY_WEIGHT_DECAY}^rank) -- see this class's own doc
     * for why this replaced the old flat-distance-floor random pick.
     */
    private RoadwayStakeEntity pickRecencyWeightedTarget(ServerLevel level, GhostTownHallCoreEntity core, List<RoadwayStakeEntity> stakes) {
        List<RoadwayStakeEntity> eligible = new ArrayList<>();
        long now = level.getGameTime();
        for (RoadwayStakeEntity stake : stakes) {
            if (stake.isClaimed(now)) {
                continue;
            }
            if (!PlotGeometry.isWithinAnyTownProper(level, core, Mth.floor(stake.getX()), Mth.floor(stake.getZ()))) {
                continue;
            }
            eligible.add(stake);
        }
        if (eligible.isEmpty()) {
            return null;
        }
        eligible.sort(Comparator.comparingLong(RoadwayStakeEntity::lastVisitedGameTime));
        double[] weights = new double[eligible.size()];
        double totalWeight = 0.0;
        for (int i = 0; i < eligible.size(); i++) {
            weights[i] = Math.pow(RECENCY_WEIGHT_DECAY, i);
            totalWeight += weights[i];
        }
        double roll = guard.getRandom().nextDouble() * totalWeight;
        double cumulative = 0.0;
        for (int i = 0; i < eligible.size(); i++) {
            cumulative += weights[i];
            if (roll < cumulative) {
                return eligible.get(i);
            }
        }
        return eligible.get(eligible.size() - 1); // floating-point fallback, should be unreachable
    }

    private RoadwayStakeEntity nearestStake(List<RoadwayStakeEntity> stakes) {
        RoadwayStakeEntity nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (RoadwayStakeEntity stake : stakes) {
            double distSq = guard.distanceToSqr(stake);
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = stake;
            }
        }
        return nearest;
    }

    /** Unweighted BFS over {@link RoadwayStakeEntity#getConnectionIds()} -- empty if {@code goal} isn't reachable from {@code start} (a disconnected network component). Delegates to the shared {@link com.github.cerealklla.settlemynts.roadway.RoadwayGraph}, added 2026-10-11 so the new Highway Stake feature can reuse the exact same algorithm. */
    private static List<RoadwayStakeEntity> findStakePath(List<RoadwayStakeEntity> stakes, RoadwayStakeEntity start, RoadwayStakeEntity goal) {
        return com.github.cerealklla.settlemynts.roadway.RoadwayGraph.shortestPath(stakes, start, goal);
    }

    private BlockPos pickTarget(ServerLevel level, Geometry.Polygon area) {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Geometry.Polygon.Vertex v : area.vertices()) {
            minX = Math.min(minX, v.x());
            minZ = Math.min(minZ, v.z());
            maxX = Math.max(maxX, v.x());
            maxZ = Math.max(maxZ, v.z());
        }
        int currentX = Mth.floor(guard.getX());
        int currentZ = Mth.floor(guard.getZ());

        if (!area.contains(currentX, currentZ)) {
            int centerX = (minX + maxX) / 2;
            int centerZ = (minZ + maxZ) / 2;
            for (int attempt = 0; attempt < MAX_SAMPLE_ATTEMPTS; attempt++) {
                int x = centerX + guard.getRandom().nextInt(5) - 2;
                int z = centerZ + guard.getRandom().nextInt(5) - 2;
                if (area.contains(x, z)) {
                    return surfacePos(level, x, z, guard.getY());
                }
            }
            return surfacePos(level, centerX, centerZ, guard.getY()); // best-effort even if the box center itself isn't contained (concave plot)
        }

        int rangeX = Math.max(1, maxX - minX + 1);
        int rangeZ = Math.max(1, maxZ - minZ + 1);
        for (int attempt = 0; attempt < MAX_SAMPLE_ATTEMPTS; attempt++) {
            int x = minX + guard.getRandom().nextInt(rangeX);
            int z = minZ + guard.getRandom().nextInt(rangeZ);
            if (area.contains(x, z)) {
                return surfacePos(level, x, z, guard.getY());
            }
        }
        return null; // No contained sample found this cycle -- canUse() just tries again later.
    }

    /** GroundHeightFinder, not a raw Heightmap lookup -- see that class's own doc (2026-10-29 Nether-ceiling fix). Anchored to the guard's own current Y, a real position it's already standing at. */
    private static BlockPos surfacePos(ServerLevel level, int x, int z, double referenceY) {
        int y = com.github.cerealklla.settlemynts.util.GroundHeightFinder.findNear(level, x, z, Mth.floor(referenceY));
        return new BlockPos(x, y, z);
    }
}
