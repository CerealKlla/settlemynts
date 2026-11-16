package com.github.cerealklla.settlemynts.guardhouse;

import java.util.EnumSet;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * Diverts an idle guard to a {@link PriorityLocation} ahead of its normal patrol rotation -- explicit
 * user spec, 2026-11-15: "with Priority locations, this is first in line on every guard's list."
 * Priority 3 in {@code GuardEntity#registerGoals} -- one step above {@link GuardPatrolAreaGoal}'s
 * priority 4, so this wins the MOVE flag whenever both would otherwise be eligible, but still defers
 * entirely (see {@link #canUse}/{@link #canContinueToUse}) the instant {@code guard.getTarget()} is
 * set, letting the real combat goals (priority 2-3, driven independently by {@link GuardSightGoal}'s
 * own {@code targetSelector}) take over exactly the same way they already preempt normal patrol.
 *
 * <p>Deliberately a single direct hop, not a multi-waypoint road route like {@link
 * GuardPatrolAreaGoal} -- a Priority Location is a raw world position, not part of the settlement's
 * {@code RoadwayStakeEntity} graph, so there's nothing to route through.
 *
 * <p><b>Runs, doesn't walk</b> -- explicit user spec ("they should also 'run' to the location, like
 * companions can run"), reusing {@code tavern.CompanionFollowPlayerGoal}'s own
 * {@code ON_FOOT_RUN_SPEED_MODIFIER} value directly: a generic {@code Mob} has no real sprint state
 * of its own, so "running" is purely a matter of the {@code speedModifier} handed to
 * {@code Navigation#moveTo} -- see that class's own doc for why.
 *
 * <p><b>Claim released on arrival, not held for the whole fight</b> -- mirrors {@link
 * GuardPatrolAreaGoal}'s own stake-claim-releases-on-arrival precedent exactly. This is a deliberate
 * choice, not an oversight: since {@link PriorityLocation#level()} keeps climbing for as long as the
 * fight stays active (new distinct mobs sighted, or continued damage to any settlement NPC nearby),
 * releasing on arrival lets fresh slots open for the *next* wave of idle guards the moment this one
 * commits to actually fighting there -- producing the explicit user-specified "if fighting continues
 * for a prolonged period, more and more guards should keep coming" behavior as a steady trickle of
 * reinforcements, rather than N guards being tied up forever the instant they first arrive.
 */
public class GuardPriorityResponseGoal extends Goal {

    // Same value as tavern.CompanionFollowPlayerGoal#ON_FOOT_RUN_SPEED_MODIFIER -- explicit user
    // spec to match that existing "run" behavior exactly, not an independently-tuned constant.
    private static final double RUN_SPEED_MODIFIER = 1.5;
    private static final double ARRIVE_DIST_SQ = 4.0;

    private final GuardEntity guard;
    private PriorityLocation target;

    public GuardPriorityResponseGoal(GuardEntity guard) {
        this.guard = guard;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (guard.getTarget() != null) {
            return false; // already in combat -- let the real attack goals run
        }
        if (!(guard.level() instanceof ServerLevel level)) {
            return false;
        }
        if (guard.homeDimension() != null && level.dimension() != guard.homeDimension()) {
            return false; // same dimension-safety check GuardPatrolAreaGoal itself already applies
        }
        UUID settlementCoreId = guard.effectiveSettlementCoreId();
        if (settlementCoreId == null) {
            return false;
        }
        PriorityLocation candidate = PriorityLocationRegistry.findClaimable(settlementCoreId, guard.blockPosition());
        if (candidate == null || !candidate.claim(guard.getUUID())) {
            return false; // none open, or lost the race to another guard between lookup and claim
        }
        target = candidate;
        guard.setClaimedPriorityLocation(candidate);
        return true;
    }

    @Override
    public void start() {
        if (target == null) {
            return;
        }
        BlockPos pos = target.pos();
        guard.getNavigation().moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, RUN_SPEED_MODIFIER);
    }

    @Override
    public boolean canContinueToUse() {
        if (target == null || guard.getTarget() != null) {
            return false; // cleared already, or real combat took over -- either way, stop() below releases the claim
        }
        BlockPos pos = target.pos();
        double dx = guard.getX() - (pos.getX() + 0.5);
        double dz = guard.getZ() - (pos.getZ() + 0.5);
        if (dx * dx + dz * dz <= ARRIVE_DIST_SQ) {
            return false; // arrived
        }
        return !guard.getNavigation().isDone(); // still actively pathing; isDone() with no arrival means stuck/failed
    }

    @Override
    public void stop() {
        if (target != null) {
            target.release(guard.getUUID());
        }
        target = null;
        guard.setClaimedPriorityLocation(null);
    }
}
