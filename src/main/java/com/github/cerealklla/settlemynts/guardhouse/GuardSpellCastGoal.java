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
 * Section 24) -- explicit user spec, called out as "very important": "They should prioritize
 * Healing nearby allies who are &lt; 50% hp, then combat. Combat using spells at range, and while
 * on cooldown melee." Swapped in by {@link GuardEntity#reassessWeaponGoal} alongside {@code
 * meleeGoal} at a *lower* priority number (this goal first) -- the melee fallback needs no manual
 * logic here at all: when this goal's {@link #canUse} returns false (nothing to heal, spell on
 * cooldown or out of mana), vanilla's own {@code GoalSelector} simply falls through to the
 * lower-priority melee goal automatically, since both goals want the same {@code MOVE}/{@code
 * LOOK} flags and only one can run at a time.
 *
 * <p>"Ally" for the heal-priority scan is "any non-hostile {@code LivingEntity}" -- a Guard is
 * never itself an {@link Enemy}, so this already matches the alignment-symmetric rule {@code
 * magician.SparkOfLifeProjectile} itself uses to decide heal-vs-damage on hit (same-alignment =
 * ally); duplicated here rather than round-tripped through Lyfe since it's a plain, one-line
 * filter and this goal's only job is *target selection*, not the actual heal/damage decision.
 */
public final class GuardSpellCastGoal extends Goal {

    private static final double HEAL_SCAN_RADIUS = 16.0;
    private static final double CAST_RANGE = 15.0;
    private static final double CAST_RANGE_SQR = CAST_RANGE * CAST_RANGE;

    private final GuardEntity guard;

    public GuardSpellCastGoal(GuardEntity guard) {
        this.guard = guard;
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (findInjuredAlly() != null) {
            return true;
        }
        LivingEntity enemy = guard.getTarget();
        return enemy != null && enemy.isAlive() && LyfeMagicianBridge.canCastSparkOfLife(guard);
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
        LivingEntity injuredAlly = findInjuredAlly();
        if (injuredAlly != null) {
            engage(injuredAlly);
            return;
        }
        LivingEntity enemy = guard.getTarget();
        if (enemy != null && enemy.isAlive()) {
            engage(enemy);
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

    private LivingEntity findInjuredAlly() {
        // Temporary diagnostic, 2026-10-20 (see npc.NpcTreetopEscape's own identical addition -- real
        // report of sustained 140-280ms ticks near a settlement; this runs every tick, unthrottled, for
        // every staff-wielding guard). Remove once the real culprit is confirmed.
        long diagStart = System.nanoTime();
        AABB scanBox = guard.getBoundingBox().inflate(HEAL_SCAN_RADIUS);
        List<LivingEntity> candidates = guard.level().getEntitiesOfClass(LivingEntity.class, scanBox,
                e -> e != guard && e.isAlive() && !(e instanceof Enemy) && e.getHealth() < e.getMaxHealth() * 0.5F);
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
