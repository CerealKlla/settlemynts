package com.github.cerealklla.settlemynts.guardhouse;

import java.util.EnumSet;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Real user spec, 2026-10-29: a Guard that ends up outside its home dimension (e.g. chasing a
 * hostile mob through a Nether Portal while legitimately defending the town) should path to the
 * nearest portal and walk through it naturally once there's nothing left to fight or heal -- not
 * get yanked back with an instant teleport. Deliberately NOT portal-blocking/immune (an earlier
 * same-session attempt at that was built, then reverted) -- a Guard crossing dimensions through a
 * real portal, in either direction, has to stay possible, since the user explicitly wants to keep
 * the door open for a future Nether trade route.
 *
 * <p><b>Real bug fix, 2026-10-29, same day</b> -- "I have made a settlement in the nether with a
 * guardhouse... The guards are immediately walking to the nether portal in the settlement... and
 * standing directly under it, no matter what." Root cause: {@link #isStrandedWithNoTarget}
 * originally hardcoded "home" as {@code Level.OVERWORLD} -- correct for every settlement built so
 * far, but simply wrong for one genuinely founded in the Nether, where it made this goal's
 * {@code canUse()} return {@code true} *permanently* (every guard is always "not in the Overworld"
 * there), completely starving {@link GuardPatrolAreaGoal}/the weapon goals at the same priority tier
 * of ever running at all. Now compares against {@link GuardEntity#homeDimension()} -- the real
 * dimension each guard actually spawned into (see {@code GuardSpawnTicker}) -- instead of a fixed
 * dimension, so a Nether settlement's own guards correctly read as "home" there and patrol normally;
 * only a guard that's truly strayed into a *different* dimension than its own settlement still
 * triggers this goal. A guard saved before this field existed resolves its real home dimension from
 * its own Guard Kit Stand ({@link GuardKitStandIndex}, see {@link #isStrandedWithNoTarget}'s own
 * doc for a real correction to this migration logic) rather than falling back to the same hardcoded
 * assumption that caused the bug -- so an existing Nether settlement's already-spawned guards
 * self-correct immediately, with no need to wait for them to die and respawn.
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
 *
 * <p><b>Real bug fix, 2026-10-30</b> -- "a bunch of guards are just standing here" (screenshot of
 * several Guards clustered around, but not inside, a Nether portal). Root cause: every stranded
 * guard pathed to the exact same single portal interior block and relied entirely on vanilla's own
 * entity-overlaps-the-portal-block detection to actually cross -- fine for one guard, but several
 * arriving at once jostle/push each other off that one block via ordinary entity collision before
 * any of them can sit still long enough to register as "inside" it, so they end up loitering next
 * to the portal instead of crossing. Fixed by snapping a guard directly onto the portal block
 * ({@link #SNAP_TRIGGER_DISTANCE_BLOCKS}, 2 blocks out, well short of melee/spell engagement range)
 * once navigation has closed most of the distance, rather than requiring it to physically walk the
 * last stretch in — vanilla's own in-portal/dimension-crossing logic (unchanged, see this class's
 * own "deliberately not portal-blocking/immune" note above) still does the actual crossing from
 * there, this just removes the crowding that was preventing it from ever starting.
 */
public final class GuardReturnToDimensionGoal extends Goal {

    private static final double SNAP_TRIGGER_DISTANCE_BLOCKS = 2.0D;

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

    /**
     * A guard saved before {@link GuardEntity#homeDimension} existed has {@code null} there.
     *
     * <p><b>Corrected the same day, real follow-up report</b>: "There is a guard which successfully
     * left the nether to go to the overworld and now he's just standing here instead of going
     * back... he has been here since before your fix." The first version of this migration lazily
     * adopted whichever dimension the guard was *already in* as home -- wrong for precisely this
     * guard, who was already astray (legitimately chasing/healing across a portal, from a Nether
     * settlement) at the exact moment the migration ran, permanently mislearning "home" as the
     * Overworld he just happened to be standing in. Fixed by resolving the real home dimension from
     * an authoritative source instead of a guess: {@link GuardKitStandIndex} already tracks every
     * stand's real {@link GlobalPos} (dimension included) independent of chunk-load state or where
     * the guard itself currently is -- the same reason that index exists at all. Falls back to the
     * guard's current dimension only if the stand itself can't be resolved (e.g. picked up/removed),
     * matching the original, narrower fallback.
     */
    private boolean isStrandedWithNoTarget() {
        if (!(guard.level() instanceof ServerLevel level) || guard.getTarget() != null) {
            return false;
        }
        if (guard.homeDimension() == null) {
            guard.setHomeDimension(resolveHomeDimensionFromStand(level).orElse(level.dimension()));
        }
        return level.dimension() != guard.homeDimension();
    }

    private java.util.Optional<ResourceKey<Level>> resolveHomeDimensionFromStand(ServerLevel level) {
        UUID standId = guard.assignedStandId();
        if (standId == null) {
            return java.util.Optional.empty();
        }
        return GuardKitStandIndex.get(level.getServer()).get(standId).map(GlobalPos::dimension);
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

    @Override
    public void tick() {
        if (portalTarget == null) {
            return;
        }
        Vec3 portalCenter = Vec3.atCenterOf(portalTarget);
        if (guard.position().closerThan(portalCenter, SNAP_TRIGGER_DISTANCE_BLOCKS)) {
            guard.teleportTo(portalCenter.x, portalTarget.getY(), portalCenter.z);
        }
    }

    private BlockPos findNearestPortal(ServerLevel level) {
        boolean searchingInNether = level.dimension() == Level.NETHER;
        return level.getPortalForcer()
                .findClosestPortalPosition(guard.blockPosition(), searchingInNether, level.getWorldBorder())
                .orElse(null);
    }
}
