package com.github.cerealklla.settlemynts.guardhouse;

import java.util.ArrayList;
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
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Patrols the settlement's own road network (added 2026-10-06, explicit user request: "I'd like
 * the guards to walk along the roads when patrolling the settlement"). {@link #pickFarRoadTarget}
 * walks the WHOLE settlement's {@link RoadwayStakeEntity} graph (not just this guard's own plot)
 * and picks a **random** stake that's at least {@link #MIN_FAR_DISTANCE_BLOCKS} away as a {@code
 * nextFinalDestination} -- per the user's own follow-up spec: "Guards should pick a road stake
 * within the settlement which is quite far as it's nextFinalDestination, and then plot a path
 * between it's current position to that place, and then follow that path. Once it arrives at the
 * nextFinalDestination, pick a new one and repeat." Only a stake {@link
 * PlotGeometry#isWithinAnyTownProper} says is inside AT LEAST ONE of the settlement's plots' own
 * "Town Proper" buffers is eligible -- per the original spec this replaced: "you can't make a road
 * go all the way out to the edge of a settlement and a guard will just go there, they only protect
 * buildings people [live in] that they can walk to within the town itself." The actual walk there
 * is handed entirely to vanilla's own pathfinding ({@code PathNavigation#moveTo}) -- this goal just
 * issues the one long-distance order and then gets out of the way (see {@link #canContinueToUse})
 * until that single path is fully walked, rather than re-picking a new target every short interval.
 *
 * <p><b>Reworked three times now, same day</b>: v1 picked a uniformly random connection anywhere in
 * the settlement and a random point along its centerline (abandoned -- could send a guard cutting
 * cross-country toward an unrelated part of the network instead of following a road). v2 picked the
 * nearest stake more than 5 blocks away as a series of short hops (abandoned for one long committed
 * walk to a far destination instead, re-picked only on arrival). v3 picked the single FARTHEST
 * eligible stake every time (abandoned the same day -- real feedback: "I didn't say 'farthest' on
 * purpose; it'll just result in them only walking down the same path back and forth. a round/square
 * settlement would only have the diagonal path actually patrolled" -- the farthest point from any
 * given spot is deterministic, so a guard would always beeline the same extreme corner-to-corner
 * route and never cover any of the network's other branches). Picking randomly among every stake
 * past the distance floor instead gives route variety across the whole graph over repeated cycles.
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
    // The current committed long-distance target -- non-null for as long as this goal should keep
    // running (see canContinueToUse), cleared in stop() so a fresh pick happens next time (whether
    // because the old one was reached, or because a higher-priority goal like combat interrupted it).
    private BlockPos nextFinalDestination;

    public GuardPatrolAreaGoal(GuardEntity guard, double speedModifier) {
        this.guard = guard;
        this.speedModifier = speedModifier;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        return guard.getNavigation().isDone() && guard.level() instanceof ServerLevel;
    }

    @Override
    public boolean canContinueToUse() {
        // Keeps this goal "running" (so it isn't re-evaluated/re-picked) for the whole walk to
        // nextFinalDestination, only releasing once vanilla's own pathfinding reports arrival.
        return nextFinalDestination != null && !guard.getNavigation().isDone();
    }

    @Override
    public void start() {
        if (!(guard.level() instanceof ServerLevel level)) {
            return;
        }
        BlockPos target = pickFarRoadTarget(level);
        if (target == null) {
            target = guard.resolvePatrolArea(level).map(area -> pickTarget(level, area)).orElse(null);
        }
        nextFinalDestination = target;
        if (target != null) {
            guard.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, speedModifier);
        }
    }

    @Override
    public void stop() {
        nextFinalDestination = null;
    }

    // "Quite far" -- deliberately a flat distance floor, not "farthest available" (see class doc:
    // picking the single farthest stake every time always finds the same extreme point, so a guard
    // would only ever walk that one corner-to-corner diagonal and never the rest of the network).
    private static final double MIN_FAR_DISTANCE_BLOCKS = 50.0;

    private BlockPos pickFarRoadTarget(ServerLevel level) {
        UUID settlementCoreId = guard.getSettlementCoreId();
        if (settlementCoreId == null || !(level.getEntity(settlementCoreId) instanceof GhostTownHallCoreEntity core)) {
            return null;
        }
        List<RoadwayStakeEntity> stakes = RoadwayStakeEntity.findByOwnerCore(level, settlementCoreId);
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
        RoadwayStakeEntity chosen = eligible.isEmpty()
                ? closestToFloor
                : eligible.get(guard.getRandom().nextInt(eligible.size()));
        return chosen == null ? null : surfacePos(level, Mth.floor(chosen.getX()), Mth.floor(chosen.getZ()));
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
