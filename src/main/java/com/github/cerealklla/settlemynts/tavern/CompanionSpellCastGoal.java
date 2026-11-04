package com.github.cerealklla.settlemynts.tavern;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

import com.github.cerealklla.settlemynts.bridge.LyfeMagicianBridge;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

/**
 * A summoned Tavern Companion's own spell-casting behavior while wielding a Nature Staff -- explicit
 * user spec, 2026-11-01: "Use the same steps as town guards, e.g. heal allies ... etc." Direct copy
 * of {@code guardhouse.GuardSpellCastGoal} (same four-tier priority: heal allies under 50% HP, then
 * ranged spell combat, then heal-to-full once no enemy remains, then whatever's at lower priority),
 * with exactly one addition: every entry point defers while {@link TavernNpcEntity#stoolPos()} is
 * non-null -- see {@link CompanionSightGoal}'s own doc for why that's the right gate.
 *
 * <p><b>Mounted approach fixed, 2026-11-03</b> -- {@link #engage} used to drive {@code npc}'s own
 * {@code Navigation} directly, a no-op while mounted (same root cause as {@link
 * CompanionMeleeAttackGoal}/{@link CompanionRangedAttackGoal}'s own doc explains in full). Now
 * drives {@link TavernNpcEntity#mover()} instead, at {@link TavernNpcEntity#MOUNTED_COMBAT_SPEED_MODIFIER}
 * while mounted.
 */
public final class CompanionSpellCastGoal extends Goal {

    private static final double HEAL_SCAN_RADIUS = 16.0;
    private static final double CAST_RANGE = 15.0;
    private static final double CAST_RANGE_SQR = CAST_RANGE * CAST_RANGE;
    private static final float CRITICAL_HEAL_THRESHOLD = 0.5F;
    private static final float TOP_OFF_HEAL_THRESHOLD = 1.0F;

    private final TavernNpcEntity npc;
    private int repathCooldown;

    public CompanionSpellCastGoal(TavernNpcEntity npc) {
        this.npc = npc;
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public void start() {
        repathCooldown = 0;
    }

    @Override
    public boolean canUse() {
        if (npc.stoolPos() != null) {
            return false;
        }
        if (findInjuredAlly(CRITICAL_HEAL_THRESHOLD) != null) {
            return true;
        }
        LivingEntity enemy = attackTarget();
        if (enemy != null) {
            return LyfeMagicianBridge.canCastSparkOfLife(npc);
        }
        return findInjuredAlly(TOP_OFF_HEAL_THRESHOLD) != null;
    }

    private LivingEntity attackTarget() {
        LivingEntity target = npc.getTarget();
        if (target == null || !target.isAlive() || !LyfeMagicianBridge.canSparkOfLifeAffect(npc, target)) {
            return null;
        }
        return target;
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
        LivingEntity enemy = attackTarget();
        if (enemy != null) {
            engage(enemy);
            return;
        }
        LivingEntity topOffAlly = findInjuredAlly(TOP_OFF_HEAL_THRESHOLD);
        if (topOffAlly != null) {
            engage(topOffAlly);
        }
    }

    private void engage(LivingEntity target) {
        double distanceSqr = npc.distanceToSqr(target);
        boolean lineOfSight = npc.hasLineOfSight(target);
        var mover = npc.mover();
        if (distanceSqr > CAST_RANGE_SQR || !lineOfSight) {
            if (mover.getNavigation().isDone() && repathCooldown-- <= 0) {
                double speed = mover != npc ? TavernNpcEntity.MOUNTED_COMBAT_SPEED_MODIFIER : 1.0;
                mover.getNavigation().moveTo(target, speed);
                repathCooldown = 20;
            }
        } else {
            mover.getNavigation().stop();
        }
        double xd = target.getX() - npc.getX();
        double zd = target.getZ() - npc.getZ();
        double horizontalDist = Math.sqrt(xd * xd + zd * zd);
        double aimY = target.getY(0.5) + horizontalDist * 0.2;
        npc.getLookControl().setLookAt(target.getX(), aimY, target.getZ(), 30.0F, 30.0F);
        double yd = aimY - npc.getEyeY();
        npc.setXRot((float) -(Mth.atan2(yd, horizontalDist) * 180.0F / Math.PI));
        npc.setYRot((float) (Mth.atan2(zd, xd) * 180.0F / Math.PI) - 90.0F);
        if (distanceSqr <= CAST_RANGE_SQR && lineOfSight) {
            LyfeMagicianBridge.attemptCastSparkOfLife(npc);
        }
    }

    private LivingEntity findInjuredAlly(float healthFraction) {
        AABB scanBox = npc.getBoundingBox().inflate(HEAL_SCAN_RADIUS);
        List<LivingEntity> candidates = npc.level().getEntitiesOfClass(LivingEntity.class, scanBox,
                e -> e != npc && e.isAlive() && !(e instanceof Enemy) && (e instanceof Player || e instanceof Mob)
                        && e.getHealth() < e.getMaxHealth() * healthFraction
                        && LyfeMagicianBridge.canSparkOfLifeAffect(npc, e));
        return candidates.stream().min(Comparator.comparingDouble(e -> e.getHealth() / e.getMaxHealth())).orElse(null);
    }
}
