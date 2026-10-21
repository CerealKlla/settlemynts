package com.github.cerealklla.settlemynts.guardhouse;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

import com.github.cerealklla.settlemynts.bridge.LyfeMagicianBridge;

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

    @Override
    public boolean canUse() {
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
        return canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
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
        if (distanceSqr > CAST_RANGE_SQR) {
            guard.getNavigation().moveTo(target, 1.0);
        } else {
            guard.getNavigation().stop();
        }
        guard.getLookControl().setLookAt(target, 30.0F, 30.0F);
        guard.lookAt(target, 30.0F, 30.0F);
        if (distanceSqr <= CAST_RANGE_SQR) {
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
        return result;
    }
}
