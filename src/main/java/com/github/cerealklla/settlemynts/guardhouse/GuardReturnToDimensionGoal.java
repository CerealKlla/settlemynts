package com.github.cerealklla.settlemynts.guardhouse;

import java.util.EnumSet;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;

/**
 * Real user spec, 2026-10-29: a Guard that ends up outside its home dimension (e.g. chasing a
 * hostile mob through a Nether Portal while legitimately defending the town) should path to the
 * nearest portal and walk through it naturally once there's nothing left to fight or heal -- not
 * get yanked back with an instant teleport. Deliberately NOT portal-blocking/immune (an earlier
 * same-session attempt at that was built, then reverted) -- a Guard crossing dimensions through a
 * real portal, in either direction, has to stay possible, since the user explicitly wants to keep
 * the door open for a future Nether trade route.
 *
 * <p>{@link GuardEntity#getTarget()} doubles as the "actively engaged" signal for both combat AND
 * healing (see {@code GuardSpellCastGoal#engage}'s own doc -- it sets the same field either way),
 * so checking for "no target" alone already covers "not fighting or healing allies" exactly as
 * asked, with no new tracking needed.
 *
 * <p>Finding the nearest portal reuses vanilla's own real portal lookup,
 * {@link net.minecraft.world.level.portal.PortalForcer#findClosestPortalPosition} -- the exact
 * same POI-indexed search vanilla itself uses when an entity crosses a portal and looks for an
 * existing linked one to reuse, not a hand-rolled block scan (which would be a real, needless
 * per-tick cost for every stranded guard).
 *
 * <p>Priority 2, alongside the weapon/spell goals -- safe to share that tier because the two are
 * naturally mutually exclusive by their own {@code canUse()} conditions (this goal requires no
 * target at all; the weapon/spell goals only want to run when there genuinely is one), same
 * established pattern this class already uses between {@link GuardPatrolAreaGoal} and the weapon
 * goals.
 */
public final class GuardReturnToDimensionGoal extends Goal {

    private final GuardEntity guard;
    private final double speedModifier;
    private BlockPos portalTarget;

    public GuardReturnToDimensionGoal(GuardEntity guard, double speedModifier) {
        this.guard = guard;
        this.speedModifier = speedModifier;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!isStrandedWithNoTarget()) {
            return false;
        }
        portalTarget = findNearestPortal((ServerLevel) guard.level());
        return portalTarget != null;
    }

    @Override
    public boolean canContinueToUse() {
        return isStrandedWithNoTarget() && portalTarget != null && !guard.getNavigation().isDone();
    }

    private boolean isStrandedWithNoTarget() {
        return guard.level() instanceof ServerLevel level && level.dimension() != Level.OVERWORLD && guard.getTarget() == null;
    }

    @Override
    public void start() {
        if (portalTarget != null) {
            guard.getNavigation().moveTo(portalTarget.getX() + 0.5, portalTarget.getY(), portalTarget.getZ() + 0.5, speedModifier);
        }
    }

    @Override
    public void stop() {
        portalTarget = null;
    }

    private BlockPos findNearestPortal(ServerLevel level) {
        boolean searchingInNether = level.dimension() == Level.NETHER;
        return level.getPortalForcer()
                .findClosestPortalPosition(guard.blockPosition(), searchingInNether, level.getWorldBorder())
                .orElse(null);
    }
}
