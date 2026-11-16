package com.github.cerealklla.settlemynts.guardhouse;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * A dynamic "something is wrong here" marker inside a settlement -- explicit user spec, 2026-11-15:
 * an NPC sighting a hostile, or any settlement NPC taking damage, drops one of these, and guards
 * divert from their normal patrol rotation to converge on it ahead of everything else. See {@link
 * PriorityLocationRegistry} for creation/lookup and {@link GuardPriorityResponseGoal} for the actual
 * guard response.
 *
 * <p>Deliberately not a real world {@link net.minecraft.world.entity.Entity} or persisted
 * {@code SavedData} -- these are short-lived, purely combat-adjacent bookkeeping (seconds to a
 * couple of minutes), same "non-persisted, server-memory-only" precedent Lyfe's own {@code
 * combat.AssistMarkers} already established for the identical class of problem. Losing all active
 * locations on a server restart is an accepted, harmless simplification: guards simply resume
 * normal patrol and would re-trigger fresh locations immediately if a fight is genuinely still
 * ongoing.
 *
 * <p>Position is fixed at creation and never re-centered for the sighting/damage-triggered paths --
 * every later contributing event within {@link PriorityLocationRegistry}'s own cluster radius
 * escalates this same instance's {@link #level} instead of spawning a duplicate, but doesn't move
 * {@link #pos}. This is a deliberate simplification (confirmed acceptable, "if it's easier") over
 * continuously averaging position as the fight drifts.
 *
 * <p>The zombie-villager-conversion path is the one exception, explicit user spec, 2026-11-16: "the
 * 'until it dies' was directly tied to 'centered on the zombie,' meaning if the zombie is moving so
 * is the location, so it can never walk out of the area and despawn the area." A location created
 * via {@link #trackEntity} gets re-centered onto that exact entity's live position every {@link
 * PriorityLocationRegistry#cleanup} pass instead of relying on the fixed-radius "no hostile nearby"
 * rule, and is removed the instant that specific entity can no longer be resolved alive (dead,
 * cured, or its chunk unloaded) rather than when the *area* goes quiet -- see {@link
 * PriorityLocationRegistry#cleanup}'s own doc for the exact removal rule.
 */
public final class PriorityLocation {

    private BlockPos pos;
    private final ResourceKey<Level> dimension;
    private final UUID settlementCoreId;
    private int level;
    // Non-null only for the zombie-conversion path (see this class's own doc) -- the entity this
    // location follows instead of staying fixed, and whose death/disappearance is what removes it.
    private UUID trackedEntityId;
    // Dedup for the sight-triggered path only -- a given mob (by UUID) escalates this location's
    // level at most once each, no matter how many times/how many different guards re-spot it. The
    // damage-triggered path deliberately has no equivalent dedup -- see PriorityLocationRegistry's
    // own doc, explicit user spec: "each time they take damage it's escalated again."
    private final Set<UUID> seenMobIds = new HashSet<>();
    // Guards currently committed (claimed a slot and are actively walking there) -- capped at
    // level by construction (claim() refuses once openSlots() is 0). Released the instant the
    // claiming guard arrives (GuardPriorityResponseGoal#stop()) or dies (GuardEntity#die()), NOT
    // held for the duration of the fight itself -- see GuardPriorityResponseGoal's own doc for why
    // mirroring RoadwayStakeEntity's own claim-releases-on-arrival precedent is the right call here
    // too: it produces a steady trickle of reinforcements for as long as the location stays
    // escalated, rather than N guards being tied up forever the instant they arrive.
    private final Set<UUID> claimedGuardIds = new HashSet<>();

    PriorityLocation(BlockPos pos, ResourceKey<Level> dimension, UUID settlementCoreId) {
        this.pos = pos;
        this.dimension = dimension;
        this.settlementCoreId = settlementCoreId;
    }

    public BlockPos pos() {
        return pos;
    }

    /** Re-centers a tracked location onto its followed entity's current position -- see this class's own doc. */
    void moveTo(BlockPos newPos) {
        this.pos = newPos;
    }

    /** Marks this location as following {@code entityId}'s live position instead of staying fixed -- see this class's own doc. Idempotent; a repeat call with the same id is a no-op. */
    void trackEntity(UUID entityId) {
        this.trackedEntityId = entityId;
    }

    UUID trackedEntityId() {
        return trackedEntityId;
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    public UUID settlementCoreId() {
        return settlementCoreId;
    }

    public int level() {
        return level;
    }

    /** +1 to level the first time {@code mobId} contributes to this location, a no-op on a repeat. */
    void registerSeenMob(UUID mobId) {
        if (seenMobIds.add(mobId)) {
            level++;
        }
    }

    /** Unconditional +1 -- no dedup, explicit user spec (see class doc). */
    void escalateFromDamage() {
        level++;
    }

    /** {@code level} minus however many guards are already committed -- never negative. */
    public int openSlots() {
        return Math.max(0, level - claimedGuardIds.size());
    }

    /** {@code true} and reserves a slot if one was open; {@code false} (no-op) otherwise -- callers must check the return value, since another guard can win the race between a lookup and this call. */
    public boolean claim(UUID guardId) {
        if (openSlots() <= 0) {
            return false;
        }
        return claimedGuardIds.add(guardId);
    }

    public void release(UUID guardId) {
        claimedGuardIds.remove(guardId);
    }
}
