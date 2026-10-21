package com.github.cerealklla.settlemynts.guardhouse;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

import com.github.cerealklla.settlemynts.bridge.LyfeMagicianBridge;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;

/**
 * A Guard's spell-casting behavior while wielding a Nature Staff (Lyfe's Mana feature, design doc
 * Section 24). Four-tier priority, explicit user spec (2026-10-20, correcting the original
 * "heal &lt;50%, then combat, melee on cooldown" shape -- that order starved any ally sitting
 * between 50% and 100% forever once an enemy showed up and never let go):
 * <ol>
 *   <li>Heal allies under 50% HP (critical -- always wins, even mid-combat).
 *   <li>Attack a nearby enemy -- at range via Spark of Life if available; otherwise this goal
 *       defers entirely ({@link #canUse} returns {@code false}) so vanilla's own {@code
 *       GoalSelector} falls through to the lower-priority melee goal instead of healing to full
 *       while an enemy is still around.
 *   <li>Heal allies under 100% HP (top-off) -- only once no enemy is present at all.
 *   <li>Whatever the Guard does at rest (patrol) -- this goal's own fallback, once nothing above
 *       applies.
 * </ol>
 * Swapped in by {@link GuardEntity#reassessWeaponGoal} alongside {@code meleeGoal} at a *lower*
 * priority number (this goal first) -- both goals want the same {@code MOVE}/{@code LOOK} flags
 * and only one can run at a time, so tier 2's melee-while-on-cooldown case needs no manual logic
 * here at all.
 *
 * <p>"Ally" for both heal scans is "any non-hostile {@code LivingEntity}" -- a Guard is never
 * itself an {@link Enemy}, so this already matches the alignment-symmetric rule {@code
 * magician.SparkOfLifeProjectile} itself uses to decide heal-vs-damage on hit (same-alignment =
 * ally); duplicated here rather than round-tripped through Lyfe since it's a plain, one-line
 * filter and this goal's only job is *target selection*, not the actual heal/damage decision.
 */
public final class GuardSpellCastGoal extends Goal {

    private static final double HEAL_SCAN_RADIUS = 16.0;
    private static final double CAST_RANGE = 15.0;
    private static final double CAST_RANGE_SQR = CAST_RANGE * CAST_RANGE;
    private static final float CRITICAL_HEAL_THRESHOLD = 0.5F;
    private static final float TOP_OFF_HEAL_THRESHOLD = 1.0F;

    private final GuardEntity guard;

    public GuardSpellCastGoal(GuardEntity guard) {
        this.guard = guard;
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    // Temporary instrumentation (2026-10-21) -- see diagnostics.TickProfiler's own doc. This goal is
    // a prime suspect: requiresUpdateEveryTick()=true means tick() runs every tick while active, AND
    // canContinueToUse() (checked every tick by vanilla's own GoalSelector while active) fully
    // delegates back to canUse() -- so a single staff-wielding Guard can call findInjuredAlly (its
    // own 16-block-radius AABB entity scan, completely unthrottled unlike GuardSightGoal's 10-tick
    // cooldown) up to 4 times in one tick. Wrapped separately (not just findInjuredAlly itself) so
    // the breakdown can directly show whether this duplication is the real cost.
    @Override
    public boolean canUse() {
        return com.github.cerealklla.settlemynts.diagnostics.TickProfiler.timeValue("GuardSpellCastGoal.canUse", this::canUseInternal);
    }

    private boolean canUseInternal() {
        if (findInjuredAlly(CRITICAL_HEAL_THRESHOLD) != null) {
            return true;
        }
        LivingEntity enemy = guard.getTarget();
        if (enemy != null && enemy.isAlive()) {
            // An enemy is present -- either cast at it, or defer to melee entirely (tier 2 still
            // "wins" over tier 3 even on cooldown; never top-off heal while there's something to fight).
            return LyfeMagicianBridge.canCastSparkOfLife(guard);
        }
        return findInjuredAlly(TOP_OFF_HEAL_THRESHOLD) != null;
    }

    @Override
    public boolean canContinueToUse() {
        return com.github.cerealklla.settlemynts.diagnostics.TickProfiler.timeValue("GuardSpellCastGoal.canContinueToUse", this::canUse);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        com.github.cerealklla.settlemynts.diagnostics.TickProfiler.time("GuardSpellCastGoal.tick", this::tickInternal);
    }

    private void tickInternal() {
        LivingEntity criticalAlly = findInjuredAlly(CRITICAL_HEAL_THRESHOLD);
        if (criticalAlly != null) {
            engage(criticalAlly);
            return;
        }
        LivingEntity enemy = guard.getTarget();
        if (enemy != null && enemy.isAlive()) {
            engage(enemy);
            return;
        }
        LivingEntity topOffAlly = findInjuredAlly(TOP_OFF_HEAL_THRESHOLD);
        if (topOffAlly != null) {
            engage(topOffAlly);
        }
    }

    private void engage(LivingEntity target) {
        double distanceSqr = guard.distanceToSqr(target);
        boolean lineOfSight = guard.hasLineOfSight(target);
        // Real report, 2026-10-20: "shooting into walls, like they don't care if they can't hit
        // their target" -- this only ever gated on distance, never a clear shot, same bug a
        // straight-line projectile with no homing can't hide. Same line-of-sight check
        // GuardSightGoal already uses for target *acquisition* -- here it's needed again for the
        // actual firing moment, since a target can duck behind cover after being acquired. Applies
        // to both heal and attack casts -- a wall blocks a heal bolt exactly as well as a damage one.
        //
        // Real follow-up report, 2026-10-21: "the NPCs don't move to get line of sight when they
        // can't hit their target, they just turn in place" -- the original fix above stopped the
        // guard from firing through a wall, but still called getNavigation().stop() whenever within
        // CAST_RANGE regardless of whether the shot was actually clear, so a blocked-but-in-range
        // guard froze in place forever (only its look control moved). Now keeps moving toward the
        // target whenever the shot isn't both in range AND clear -- lets the pathfinder route around
        // whatever's blocking line of sight instead of standing still.
        if (distanceSqr > CAST_RANGE_SQR || !lineOfSight) {
            guard.getNavigation().moveTo(target, 1.0);
        } else {
            guard.getNavigation().stop();
        }
        // Real report, 2026-10-21: "Guard NPCs are not accounting for 'bullet drop' and any kind
        // of range consistenly shoot short of the target." Spark of Life is a gravity-affected
        // throwable (SparkOfLifeProjectile) with no built-in drop compensation of its own --
        // SparkOfLifeCasting#attemptCast just fires along whatever direction the caster is
        // currently looking, which is fine for a player (aims by eye, compensates naturally) but
        // not for an NPC whose look aims directly at the target's real position with no arc. Same
        // heuristic GuardEntity's own bow code already uses for an identical problem
        // (AbstractSkeleton#performRangedAttack, confirmed against the decompiled source): aim a
        // bit above the target, scaled by horizontal distance, instead of solving the exact
        // ballistic arc.
        double xd = target.getX() - guard.getX();
        double zd = target.getZ() - guard.getZ();
        double horizontalDist = Math.sqrt(xd * xd + zd * zd);
        double aimY = target.getY(0.5) + horizontalDist * 0.2;
        guard.getLookControl().setLookAt(target.getX(), aimY, target.getZ(), 30.0F, 30.0F);
        // The look control above only applies its own smoothing on a later tick -- casting happens
        // immediately below, in this same tick, so the actual fired pitch needs to be set directly
        // here too (same trig Mob#lookAt uses internally for its Entity-based overload, duplicated
        // since its own rotlerp smoothing helper is private and this needs a plain coordinate, not
        // an Entity, to aim at the elevated point rather than the target's real position).
        double yd = aimY - guard.getEyeY();
        guard.setXRot((float) -(Mth.atan2(yd, horizontalDist) * 180.0F / Math.PI));
        guard.setYRot((float) (Mth.atan2(zd, xd) * 180.0F / Math.PI) - 90.0F);
        if (distanceSqr <= CAST_RANGE_SQR && lineOfSight) {
            LyfeMagicianBridge.attemptCastSparkOfLife(guard);
        }
    }

    private LivingEntity findInjuredAlly(float healthFraction) {
        // Temporary diagnostic, 2026-10-20 (see npc.NpcTreetopEscape's own identical addition -- real
        // report of sustained 140-280ms ticks near a settlement; this runs every tick, unthrottled, for
        // every staff-wielding guard). Remove once the real culprit is confirmed.
        long diagStart = System.nanoTime();
        AABB scanBox = guard.getBoundingBox().inflate(HEAL_SCAN_RADIUS);
        List<LivingEntity> candidates = guard.level().getEntitiesOfClass(LivingEntity.class, scanBox,
                e -> e != guard && e.isAlive() && !(e instanceof Enemy) && e.getHealth() < e.getMaxHealth() * healthFraction);
        LivingEntity result = candidates.stream().min(Comparator.comparingDouble(e -> e.getHealth() / e.getMaxHealth())).orElse(null);
        long diagElapsedNanos = System.nanoTime() - diagStart;
        if (diagElapsedNanos >= 2_000_000L) {
            com.github.cerealklla.settlemynts.SettlemyntsMod.LOGGER.info(
                    "[LagDiagnostics] GuardSpellCastGoal.findInjuredAlly took {}ms ({} candidates)",
                    diagElapsedNanos / 1_000_000L, candidates.size());
        }
        com.github.cerealklla.settlemynts.diagnostics.TickProfiler.record("GuardSpellCastGoal.findInjuredAlly", diagElapsedNanos);
        return result;
    }
}
