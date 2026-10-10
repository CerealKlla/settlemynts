package com.github.cerealklla.settlemynts.guardhouse;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.github.cerealklla.cartographyr.geo.Geometry;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.roadway.RoadwayStakeEntity;
import com.github.cerealklla.settlemynts.zone.PlotGeometry;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Patrols the settlement's own road network (added 2026-10-06, explicit user request: "I'd like
 * the guards to walk along the roads when patrolling the settlement"). {@link #pickFarRoadTarget}
 * walks the WHOLE settlement's {@link RoadwayStakeEntity} graph (not just this guard's own plot)
 * and picks a **random** stake that's at least {@link #MIN_FAR_DISTANCE_BLOCKS} away as the final
 * destination -- per the user's own follow-up spec: "Guards should pick a road stake within the
 * settlement which is quite far as it's nextFinalDestination, and then plot a path between it's
 * current position to that place, and then follow that path. Once it arrives at the
 * nextFinalDestination, pick a new one and repeat."
 *
 * <p><b>Reworked 2026-10-09 -- real report: "guards aren't properly selecting a far away target
 * and then building a path from where they are to that location going from connected road stake to
 * connected road stakes."</b> The previous version handed the whole long-distance order to vanilla's
 * own {@code PathNavigation#moveTo} as a single call, trusting its A* to roughly hug the paved
 * blocks -- it doesn't reliably do that over long distances, and nothing about it actually walks
 * the stake graph's own connections. {@link #findStakePath} now runs a real breadth-first search
 * over the graph (each {@link RoadwayStakeEntity#getConnectionIds()} edge, unweighted -- the graph
 * is small and connections are already roughly uniform in length) from the nearest stake to the
 * guard's current position to the chosen far destination stake, producing an ordered hop list. Each
 * hop is issued as its own short {@code moveTo} call in turn (see {@link #canContinueToUse}, which
 * now advances {@link #waypointIndex} once a hop is reached rather than only ever tracking one
 * single final destination) -- so the guard's actual walked route now visibly follows stake to
 * connected stake, matching how the road was physically built, instead of vanilla's pathfinder
 * picking its own shortcut across open ground between two distant points. Falls back to a single
 * direct hop if the destination stake is disconnected from the guard's entry point (a malformed or
 * still-being-built network), and to the original plot-wander behavior if no roads/stakes exist at
 * all for this settlement.
 *
 * <p>Only a stake {@link PlotGeometry#isWithinAnyTownProper} says is inside AT LEAST ONE of the
 * settlement's plots' own "Town Proper" buffers is eligible as a final DESTINATION -- per the
 * original spec: "you can't make a road go all the way out to the edge of a settlement and a guard
 * will just go there, they only protect buildings people [live in] that they can walk to within the
 * town itself." Intermediate hops along the way are not filtered by this -- the path just follows
 * whatever route the graph actually has between the entry point and the eligible destination.
 *
 * <p><b>Reworked three times before that, same day (2026-10-06)</b>: v1 picked a uniformly random
 * connection anywhere in the settlement and a random point along its centerline (abandoned -- could
 * send a guard cutting cross-country toward an unrelated part of the network instead of following a
 * road). v2 picked the nearest stake more than 5 blocks away as a series of short hops (abandoned
 * for one long committed walk to a far destination instead, re-picked only on arrival). v3 picked
 * the single FARTHEST eligible stake every time (abandoned the same day -- real feedback: "I didn't
 * say 'farthest' on purpose; it'll just result in them only walking down the same path back and
 * forth. a round/square settlement would only have the diagonal path actually patrolled" -- the
 * farthest point from any given spot is deterministic, so a guard would always beeline the same
 * extreme corner-to-corner route and never cover any of the network's other branches). Picking
 * randomly among every stake past the distance floor instead gives route variety across the whole
 * graph over repeated cycles.
 *
 * <p>If no roads exist yet for this settlement (or no stake is currently eligible), falls back to
 * the original v1 behavior: a random point inside the guard's own plot's "Town Proper" buffer (see
 * {@link GuardEntity#resolvePatrolArea}), so a guard isn't simply frozen before any roads are built.
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

    private final GuardEntity guard;
    private final double speedModifier;
    // The current committed hop target -- non-null for as long as this goal should keep running (see
    // canContinueToUse), cleared in stop() so a fresh pick happens next time (whether because the
    // whole route finished, or because a higher-priority goal like combat interrupted it).
    private BlockPos nextFinalDestination;
    // The full ordered sequence of hops (stake-to-connected-stake) from the entry point to the final
    // destination, resolved once in start() -- empty for the single-hop plot-wander fallback.
    private List<BlockPos> currentRoute = List.of();
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

    @Override
    public boolean canContinueToUse() {
        if (nextFinalDestination == null) {
            return false;
        }
        double dx = guard.getX() - (nextFinalDestination.getX() + 0.5);
        double dz = guard.getZ() - (nextFinalDestination.getZ() + 0.5);
        boolean arrived = guard.getNavigation().isDone() || (dx * dx + dz * dz) <= ARRIVE_DIST_SQ;
        if (!arrived) {
            return true;
        }
        if (waypointIndex + 1 < currentRoute.size()) {
            waypointIndex++;
            nextFinalDestination = currentRoute.get(waypointIndex);
            guard.getNavigation().moveTo(
                    nextFinalDestination.getX() + 0.5, nextFinalDestination.getY(), nextFinalDestination.getZ() + 0.5, speedModifier);
            return true;
        }
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
        List<BlockPos> route = buildRoadRoute(level);
        long diagElapsedMs = (System.nanoTime() - diagStart) / 1_000_000L;
        if (diagElapsedMs >= 5) {
            com.github.cerealklla.settlemynts.SettlemyntsMod.LOGGER.info("[LagDiagnostics] GuardPatrolAreaGoal.buildRoadRoute took {}ms", diagElapsedMs);
        }
        if (route.isEmpty()) {
            BlockPos fallback = guard.resolvePatrolArea(level).map(area -> pickTarget(level, area)).orElse(null);
            route = fallback == null ? List.of() : List.of(fallback);
        }
        currentRoute = route;
        waypointIndex = 0;
        nextFinalDestination = route.isEmpty() ? null : route.get(0);
        if (nextFinalDestination != null) {
            guard.getNavigation().moveTo(
                    nextFinalDestination.getX() + 0.5, nextFinalDestination.getY(), nextFinalDestination.getZ() + 0.5, speedModifier);
        }
    }

    @Override
    public void stop() {
        nextFinalDestination = null;
        currentRoute = List.of();
        waypointIndex = 0;
    }

    // "Quite far" -- deliberately a flat distance floor, not "farthest available" (see class doc:
    // picking the single farthest stake every time always finds the same extreme point, so a guard
    // would only ever walk that one corner-to-corner diagonal and never the rest of the network).
    private static final double MIN_FAR_DISTANCE_BLOCKS = 50.0;

    /**
     * Picks a far, eligible destination stake, finds the nearest stake to the guard's current
     * position as the entry point onto the road graph, and returns the real BFS hop path between
     * them (ground-surface positions, entry point excluded since the guard is already there) --
     * empty if this settlement has no roads/stakes at all, or no eligible destination exists.
     */
    private List<BlockPos> buildRoadRoute(ServerLevel level) {
        UUID settlementCoreId = guard.getSettlementCoreId();
        if (settlementCoreId == null || !(level.getEntity(settlementCoreId) instanceof GhostTownHallCoreEntity core)) {
            return List.of();
        }
        List<RoadwayStakeEntity> stakes = RoadwayStakeEntity.findByOwnerCore(level, settlementCoreId);
        if (stakes.isEmpty()) {
            return List.of();
        }
        RoadwayStakeEntity destination = pickFarRoadTarget(level, core, stakes);
        if (destination == null) {
            return List.of();
        }
        RoadwayStakeEntity entry = nearestStake(stakes);
        if (entry == null) {
            return List.of();
        }
        List<RoadwayStakeEntity> path = findStakePath(stakes, entry, destination);
        List<RoadwayStakeEntity> hops = path.size() >= 2 ? path.subList(1, path.size()) : List.of(destination);
        List<BlockPos> route = new ArrayList<>(hops.size());
        for (RoadwayStakeEntity stake : hops) {
            route.add(surfacePos(level, Mth.floor(stake.getX()), Mth.floor(stake.getZ())));
        }
        return route;
    }

    private RoadwayStakeEntity pickFarRoadTarget(ServerLevel level, GhostTownHallCoreEntity core, List<RoadwayStakeEntity> stakes) {
        List<RoadwayStakeEntity> eligible = new ArrayList<>();
        // Second-choice pool for when the settlement just isn't big enough to have anything past
        // MIN_FAR_DISTANCE_BLOCKS yet -- the stake whose distance comes CLOSEST to that floor,
        // rather than giving up on road-patrolling entirely and falling back to plot-wandering.
        RoadwayStakeEntity closestToFloor = null;
        double closestToFloorDiff = Double.MAX_VALUE;
        for (RoadwayStakeEntity stake : stakes) {
            double dist = Math.sqrt(guard.distanceToSqr(stake));
            if (!PlotGeometry.isWithinAnyTownProper(level, core, Mth.floor(stake.getX()), Mth.floor(stake.getZ()))) {
                continue;
            }
            if (dist > MIN_FAR_DISTANCE_BLOCKS) {
                eligible.add(stake);
                continue;
            }
            double diff = Math.abs(MIN_FAR_DISTANCE_BLOCKS - dist);
            if (diff < closestToFloorDiff) {
                closestToFloor = stake;
                closestToFloorDiff = diff;
            }
        }
        return eligible.isEmpty() ? closestToFloor : eligible.get(guard.getRandom().nextInt(eligible.size()));
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

    /** Unweighted BFS over {@link RoadwayStakeEntity#getConnectionIds()} -- empty if {@code goal} isn't reachable from {@code start} (a disconnected network component). */
    private static List<RoadwayStakeEntity> findStakePath(List<RoadwayStakeEntity> stakes, RoadwayStakeEntity start, RoadwayStakeEntity goal) {
        if (start.getUUID().equals(goal.getUUID())) {
            return List.of(start);
        }
        Map<UUID, RoadwayStakeEntity> byId = new HashMap<>();
        for (RoadwayStakeEntity stake : stakes) {
            byId.put(stake.getUUID(), stake);
        }
        Map<UUID, UUID> prev = new HashMap<>();
        Set<UUID> visited = new HashSet<>();
        Deque<UUID> queue = new ArrayDeque<>();
        visited.add(start.getUUID());
        queue.add(start.getUUID());
        while (!queue.isEmpty()) {
            UUID current = queue.poll();
            if (current.equals(goal.getUUID())) {
                break;
            }
            RoadwayStakeEntity currentStake = byId.get(current);
            if (currentStake == null) {
                continue;
            }
            for (UUID next : currentStake.getConnectionIds()) {
                if (visited.add(next)) {
                    prev.put(next, current);
                    queue.add(next);
                }
            }
        }
        if (!visited.contains(goal.getUUID())) {
            return List.of();
        }
        List<UUID> idPath = new ArrayList<>();
        UUID current = goal.getUUID();
        idPath.add(current);
        while (!current.equals(start.getUUID())) {
            current = prev.get(current);
            if (current == null) {
                return List.of(); // shouldn't happen given the visited check above, but don't trust it blindly
            }
            idPath.add(current);
        }
        Collections.reverse(idPath);
        List<RoadwayStakeEntity> result = new ArrayList<>(idPath.size());
        for (UUID id : idPath) {
            RoadwayStakeEntity stake = byId.get(id);
            if (stake != null) {
                result.add(stake);
            }
        }
        return result;
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
                    return surfacePos(level, x, z);
                }
            }
            return surfacePos(level, centerX, centerZ); // best-effort even if the box center itself isn't contained (concave plot)
        }

        int rangeX = Math.max(1, maxX - minX + 1);
        int rangeZ = Math.max(1, maxZ - minZ + 1);
        for (int attempt = 0; attempt < MAX_SAMPLE_ATTEMPTS; attempt++) {
            int x = minX + guard.getRandom().nextInt(rangeX);
            int z = minZ + guard.getRandom().nextInt(rangeZ);
            if (area.contains(x, z)) {
                return surfacePos(level, x, z);
            }
        }
        return null; // No contained sample found this cycle -- canUse() just tries again later.
    }

    private static BlockPos surfacePos(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new BlockPos(x, y, z);
    }
}
