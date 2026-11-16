package com.github.cerealklla.settlemynts.guardhouse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;

/**
 * Creates/escalates/looks up {@link PriorityLocation}s -- a plain static per-settlement map, not a
 * {@code SavedData}, matching {@link PriorityLocation}'s own "non-persisted, server-memory-only"
 * design (see that class's own doc). A bare static field is a deliberate simplification specific to
 * this project's real deployment shape -- always exactly one dedicated-server JVM, never multiple
 * server instances sharing a classloader -- the same comfort level with simplicity over maximal
 * correctness this suite already shows elsewhere at this scale.
 *
 * <p><b>Clustering</b>: a new sighting/damage event within {@link #CLUSTER_RADIUS_BLOCKS} of an
 * existing active location for the same settlement escalates that one instead of creating a
 * duplicate -- explicit user spec ("any additional mobs seen within 20 blocks of that increase the
 * priority level"), reused verbatim as the damage-trigger's own clustering radius too, since no
 * separate radius was specified for that path.
 *
 * <p><b>Lifecycle</b>: there is deliberately no decay or partial expiry -- a location exists at
 * whatever level it's reached until {@link #cleanup} finds zero hostile {@link Enemy} entities left
 * within its own cluster radius, at which point it's removed outright (explicit user spec: "auto-
 * clear when no mobs are present in that area anymore, checking every 10 seconds" -- see {@link
 * PriorityLocationCleanupTicker}).
 */
public final class PriorityLocationRegistry {

    private static final double CLUSTER_RADIUS_BLOCKS = 20.0;

    private static final Map<UUID, List<PriorityLocation>> BY_SETTLEMENT = new HashMap<>();

    private PriorityLocationRegistry() {
    }

    /** The real cluster/cleanup radius every active location uses -- see this class's own doc. Exposed for {@code api.Settlemynts#findNearbyPriorityLocations}, which hands it to Lyfe's minimap so the alert-region circle it draws is never a guessed/duplicated constant. */
    public static double clusterRadiusBlocks() {
        return CLUSTER_RADIUS_BLOCKS;
    }

    /** Sight-triggered path -- {@code mobId} is the sighted hostile's own UUID, deduped per location (see {@link PriorityLocation#registerSeenMob}). */
    public static void registerSighting(ServerLevel level, UUID settlementCoreId, BlockPos pos, UUID mobId) {
        if (settlementCoreId == null) {
            return;
        }
        findOrCreate(level, settlementCoreId, pos).registerSeenMob(mobId);
    }

    /** Damage-triggered path -- {@code pos} is the damaged NPC's own position, not the attacker's. Every call escalates, unconditionally (no dedup). */
    public static void registerDamage(ServerLevel level, UUID settlementCoreId, BlockPos pos) {
        if (settlementCoreId == null) {
            return;
        }
        findOrCreate(level, settlementCoreId, pos).escalateFromDamage();
    }

    /**
     * Zombie-villager-conversion path -- see {@link PriorityLocation}'s own doc for why this one
     * follows {@code trackedEntityId}'s live position every {@link #cleanup} pass instead of
     * staying fixed. {@code pos} is only the entity's position at the moment of this call (used to
     * find/create the right location); {@link #cleanup} takes over re-centering it from here.
     */
    public static void registerTrackedThreat(ServerLevel level, UUID settlementCoreId, UUID trackedEntityId, BlockPos pos) {
        if (settlementCoreId == null) {
            return;
        }
        PriorityLocation location = findOrCreate(level, settlementCoreId, pos);
        location.registerSeenMob(trackedEntityId);
        location.trackEntity(trackedEntityId);
    }

    private static PriorityLocation findOrCreate(ServerLevel level, UUID settlementCoreId, BlockPos pos) {
        List<PriorityLocation> locations = BY_SETTLEMENT.computeIfAbsent(settlementCoreId, k -> new ArrayList<>());
        for (PriorityLocation existing : locations) {
            if (existing.pos().closerThan(pos, CLUSTER_RADIUS_BLOCKS)) {
                return existing;
            }
        }
        PriorityLocation created = new PriorityLocation(pos, level.dimension(), settlementCoreId);
        locations.add(created);
        return created;
    }

    /**
     * The nearest (to {@code fromPos}) location for {@code settlementCoreId} with at least one open
     * claim slot -- {@code null} if none exists. Explicit user spec: "move to closest priority
     * location from guard," not globally-highest-priority -- when several locations are active at
     * once, different guards naturally converge on whichever is nearest to each of them.
     */
    public static PriorityLocation findClaimable(UUID settlementCoreId, BlockPos fromPos) {
        List<PriorityLocation> locations = BY_SETTLEMENT.get(settlementCoreId);
        if (locations == null || locations.isEmpty()) {
            return null;
        }
        PriorityLocation nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (PriorityLocation candidate : locations) {
            if (candidate.openSlots() <= 0) {
                continue;
            }
            double distSq = candidate.pos().distSqr(fromPos);
            if (distSq < nearestDistSq) {
                nearest = candidate;
                nearestDistSq = distSq;
            }
        }
        return nearest;
    }

    /**
     * Every active location in {@code level}'s own dimension within {@code radiusBlocks} of {@code
     * origin}, across every settlement -- used by {@code api.Settlemynts#findNearbyPriorityLocations}
     * (Lyfe's minimap sync, added 2026-11-15, real user follow-up to this feature's own launch:
     * "Priority Locations should show upon a player's minimap if they are nearby").
     */
    public static List<PriorityLocation> findNearby(ServerLevel level, BlockPos origin, double radiusBlocks) {
        List<PriorityLocation> result = new ArrayList<>();
        for (List<PriorityLocation> locations : BY_SETTLEMENT.values()) {
            for (PriorityLocation loc : locations) {
                if (loc.dimension() == level.dimension() && loc.pos().closerThan(origin, radiusBlocks)) {
                    result.add(loc);
                }
            }
        }
        return result;
    }

    /**
     * Removes every location in {@code level} with no hostile left within its own cluster radius --
     * except a tracked (zombie-conversion) location, which is instead re-centered onto its followed
     * entity's live position and only removed once that exact entity can no longer be resolved
     * alive (dead, cured, or its chunk unloaded) -- see {@link PriorityLocationCleanupTicker}.
     */
    public static void cleanup(ServerLevel level) {
        for (List<PriorityLocation> locations : BY_SETTLEMENT.values()) {
            locations.removeIf(loc -> loc.dimension() == level.dimension() && shouldRemove(level, loc));
        }
    }

    private static boolean shouldRemove(ServerLevel level, PriorityLocation loc) {
        UUID trackedEntityId = loc.trackedEntityId();
        if (trackedEntityId == null) {
            return !hasHostileNearby(level, loc);
        }
        if (level.getEntity(trackedEntityId) instanceof LivingEntity tracked && tracked.isAlive()) {
            loc.moveTo(tracked.blockPosition());
            return false;
        }
        return true;
    }

    private static boolean hasHostileNearby(ServerLevel level, PriorityLocation location) {
        return !hostilesNearby(level, location).isEmpty();
    }

    /**
     * Debug-only ({@code /settlemynts priorityinfo}) -- the actual {@link Enemy} entities {@link
     * #cleanup} currently sees within {@code location}'s own cluster radius, i.e. the real reason
     * (if any) it isn't auto-clearing. Same query {@link #hasHostileNearby} runs, just not collapsed
     * to a boolean.
     */
    public static List<LivingEntity> hostilesNearby(ServerLevel level, PriorityLocation location) {
        // The AABB is a cheap broad-phase box query only (its corners sit up to CLUSTER_RADIUS_BLOCKS
        // * sqrt(3) away) -- the closerThan filter narrows it to the real 20-block sphere, the same
        // distance definition clustering itself uses (see findOrCreate's own closerThan check). Real
        // live bug, 2026-11-15: without this filter, hostile mobs in a cave ~20 blocks straight down
        // (horizontally close but far below the surface) fell inside the cube's corners and kept a
        // location "still a threat" forever, even with nothing actually near the settlement itself.
        AABB box = new AABB(location.pos()).inflate(CLUSTER_RADIUS_BLOCKS);
        return level.getEntitiesOfClass(LivingEntity.class, box,
                e -> e.isAlive() && e instanceof Enemy && location.pos().closerThan(e.blockPosition(), CLUSTER_RADIUS_BLOCKS));
    }

    /** Debug-only ({@code /settlemynts priorityinfo}) -- every active location in {@code level}'s own dimension, across every settlement, regardless of distance from any point. */
    public static List<PriorityLocation> allIn(ServerLevel level) {
        List<PriorityLocation> result = new ArrayList<>();
        for (List<PriorityLocation> locations : BY_SETTLEMENT.values()) {
            for (PriorityLocation loc : locations) {
                if (loc.dimension() == level.dimension()) {
                    result.add(loc);
                }
            }
        }
        return result;
    }
}
