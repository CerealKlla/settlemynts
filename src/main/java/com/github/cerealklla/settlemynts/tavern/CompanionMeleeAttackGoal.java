package com.github.cerealklla.settlemynts.tavern;

import java.util.EnumSet;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.pathfinder.Path;

/**
 * A line-for-line mirror of vanilla's real {@code MeleeAttackGoal} (confirmed against this
 * project's own decompiled NeoForm source, 2026-11-03 -- it has no clean override point at all:
 * every method calls {@code this.mob.getNavigation()} directly rather than through one overridable
 * hook, so a targeted subclass override isn't possible without reimplementing the whole class
 * anyway), with every navigation/path call redirected from {@code npc} to {@link TavernNpcEntity#mover()}
 * -- the companion's mount while riding one, otherwise the companion itself.
 *
 * <p>Real report, 2026-11-03: "when a companion riding a horse targets an enemy they should move at
 * mounted running speed to get to the target, not barely walking faster than a player walks." Root
 * cause: a mounted passenger's own {@code Navigation} never actually repositions it (vanilla only
 * applies a mob's own pathing to its real position while it's NOT a passenger), so vanilla's real
 * {@code MeleeAttackGoal} -- which this class replaces -- was issuing {@code moveTo} calls against
 * the companion's own dead {@code Navigation} the whole time; whatever tiny motion was visible was
 * just the mount's own idle wander AI. All attack-specific state (aggro, {@code doHurtTarget}, melee
 * range, line-of-sight, swing) still acts on {@code npc} itself, unchanged -- only the "which
 * {@code Navigation} gets driven, and how fast" half of the behavior changes here. {@link
 * TavernNpcEntity#MOUNTED_COMBAT_SPEED_MODIFIER} is used in place of the base {@code speedModifier}
 * whenever {@link TavernNpcEntity#mover()} resolves to a mount; on foot, behavior (including speed)
 * is identical to vanilla's own class.
 */
public class CompanionMeleeAttackGoal extends Goal {

    private final TavernNpcEntity npc;
    private final double speedModifier;
    private final boolean followingTargetEvenIfNotSeen;
    private Path path;
    private double pathedTargetX;
    private double pathedTargetY;
    private double pathedTargetZ;
    private int ticksUntilNextPathRecalculation;
    private int ticksUntilNextAttack;
    private long lastCanUseCheck;

    public CompanionMeleeAttackGoal(TavernNpcEntity npc, double speedModifier, boolean followingTargetEvenIfNotSeen) {
        this.npc = npc;
        this.speedModifier = speedModifier;
        this.followingTargetEvenIfNotSeen = followingTargetEvenIfNotSeen;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    private PathfinderMob mover() {
        return npc.mover();
    }

    private double currentSpeedModifier() {
        return mover() != npc ? TavernNpcEntity.MOUNTED_COMBAT_SPEED_MODIFIER : speedModifier;
    }

    @Override
    public boolean canUse() {
        long time = npc.level().getGameTime();
        if (time - lastCanUseCheck < 20L) {
            return false;
        }
        lastCanUseCheck = time;
        LivingEntity target = npc.getTarget();
        if (target == null || !target.isAlive()) {
            return false;
        }
        path = mover().getNavigation().createPath(target, 0);
        return path != null || npc.isWithinMeleeAttackRange(target);
    }

    @Override
    public boolean canContinueToUse() {
        LivingEntity target = npc.getTarget();
        if (target == null || !target.isAlive()) {
            return false;
        }
        if (!followingTargetEvenIfNotSeen) {
            return !mover().getNavigation().isDone();
        }
        return npc.isWithinHome(target.blockPosition()) && !(target instanceof Player player && (player.isSpectator() || player.isCreative()));
    }

    @Override
    public void start() {
        mover().getNavigation().moveTo(path, currentSpeedModifier());
        npc.setAggressive(true);
        ticksUntilNextPathRecalculation = 0;
        ticksUntilNextAttack = 0;
    }

    @Override
    public void stop() {
        LivingEntity target = npc.getTarget();
        if (!EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(target)) {
            npc.setTarget(null);
        }
        npc.setAggressive(false);
        mover().getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        LivingEntity target = npc.getTarget();
        if (target == null) {
            return;
        }
        npc.getLookControl().setLookAt(target, 30.0F, 30.0F);
        ticksUntilNextPathRecalculation = Math.max(ticksUntilNextPathRecalculation - 1, 0);
        if ((followingTargetEvenIfNotSeen || npc.getSensing().hasLineOfSight(target))
                && ticksUntilNextPathRecalculation <= 0
                && (pathedTargetX == 0.0 && pathedTargetY == 0.0 && pathedTargetZ == 0.0
                        || target.distanceToSqr(pathedTargetX, pathedTargetY, pathedTargetZ) >= 1.0
                        || npc.getRandom().nextFloat() < 0.05F)) {
            pathedTargetX = target.getX();
            pathedTargetY = target.getY();
            pathedTargetZ = target.getZ();
            ticksUntilNextPathRecalculation = 4 + npc.getRandom().nextInt(7);
            double targetDistanceSqr = npc.distanceToSqr(target);
            if (targetDistanceSqr > 1024.0) {
                ticksUntilNextPathRecalculation += 10;
            } else if (targetDistanceSqr > 256.0) {
                ticksUntilNextPathRecalculation += 5;
            }
            if (!mover().getNavigation().moveTo(target, currentSpeedModifier())) {
                ticksUntilNextPathRecalculation += 15;
            }
            ticksUntilNextPathRecalculation = adjustedTickDelay(ticksUntilNextPathRecalculation);
        }
        ticksUntilNextAttack = Math.max(ticksUntilNextAttack - 1, 0);
        checkAndPerformAttack(target);
    }

    private void checkAndPerformAttack(LivingEntity target) {
        if (ticksUntilNextAttack <= 0 && npc.isWithinMeleeAttackRange(target) && npc.getSensing().hasLineOfSight(target)) {
            ticksUntilNextAttack = adjustedTickDelay(20);
            npc.swing(InteractionHand.MAIN_HAND);
            npc.doHurtTarget(getServerLevel(npc), target);
        }
    }
}
