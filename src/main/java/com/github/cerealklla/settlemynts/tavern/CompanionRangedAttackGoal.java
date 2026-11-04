package com.github.cerealklla.settlemynts.tavern;

import java.util.EnumSet;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Items;

/**
 * A line-for-line mirror of vanilla's real {@code RangedBowAttackGoal} (confirmed against this
 * project's own decompiled NeoForm source, 2026-11-03 -- same "no clean override point" finding as
 * {@link CompanionMeleeAttackGoal}'s own doc explains in full), with every navigation call
 * redirected from {@code npc} to {@link TavernNpcEntity#mover()}. See that class's own doc for the
 * real root cause (a mounted passenger's own {@code Navigation} never actually repositions it) and
 * the real user report this fixes. Strafing/look-control/bow-draw/{@code performRangedAttack} all
 * still act on {@code npc} itself, unchanged -- a companion's own body does the strafing and firing
 * regardless of whether it's mounted, only approach-to-range movement redirects to the mount.
 */
public class CompanionRangedAttackGoal extends Goal {

    private final TavernNpcEntity npc;
    private final double speedModifier;
    private int attackIntervalMin;
    private final float attackRadiusSqr;
    private int attackTime = -1;
    private int seeTime;
    private boolean strafingClockwise;
    private boolean strafingBackwards;
    private int strafingTime = -1;

    public CompanionRangedAttackGoal(TavernNpcEntity npc, double speedModifier, int attackIntervalMin, float attackRadius) {
        this.npc = npc;
        this.speedModifier = speedModifier;
        this.attackIntervalMin = attackIntervalMin;
        this.attackRadiusSqr = attackRadius * attackRadius;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    private PathfinderMob mover() {
        return npc.mover();
    }

    private double currentSpeedModifier() {
        return mover() != npc ? TavernNpcEntity.MOUNTED_COMBAT_SPEED_MODIFIER : speedModifier;
    }

    private boolean isHoldingBow() {
        return npc.isHolding(Items.BOW);
    }

    @Override
    public boolean canUse() {
        return npc.getTarget() != null && isHoldingBow();
    }

    @Override
    public boolean canContinueToUse() {
        return (canUse() || !mover().getNavigation().isDone()) && isHoldingBow();
    }

    @Override
    public void start() {
        npc.setAggressive(true);
    }

    @Override
    public void stop() {
        npc.setAggressive(false);
        seeTime = 0;
        attackTime = -1;
        npc.stopUsingItem();
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
        double targetDistSqr = npc.distanceToSqr(target.getX(), target.getY(), target.getZ());
        boolean hasLineOfSight = npc.getSensing().hasLineOfSight(target);
        boolean hadLineOfSight = seeTime > 0;
        if (hasLineOfSight != hadLineOfSight) {
            seeTime = 0;
        }
        if (hasLineOfSight) {
            seeTime++;
        } else {
            seeTime--;
        }

        if (!(targetDistSqr > attackRadiusSqr) && seeTime >= 20) {
            mover().getNavigation().stop();
            strafingTime++;
        } else {
            mover().getNavigation().moveTo(target, currentSpeedModifier());
            strafingTime = -1;
        }

        if (strafingTime >= 20) {
            if (npc.getRandom().nextFloat() < 0.3) {
                strafingClockwise = !strafingClockwise;
            }
            if (npc.getRandom().nextFloat() < 0.3) {
                strafingBackwards = !strafingBackwards;
            }
            strafingTime = 0;
        }

        if (strafingTime > -1) {
            if (targetDistSqr > attackRadiusSqr * 0.75F) {
                strafingBackwards = false;
            } else if (targetDistSqr < attackRadiusSqr * 0.25F) {
                strafingBackwards = true;
            }
            npc.getMoveControl().strafe(strafingBackwards ? -0.5F : 0.5F, strafingClockwise ? 0.5F : -0.5F);
            if (npc.getControlledVehicle() instanceof Mob vehicle) {
                vehicle.lookAt(target, 30.0F, 30.0F);
            }
            npc.lookAt(target, 30.0F, 30.0F);
        } else {
            npc.getLookControl().setLookAt(target, 30.0F, 30.0F);
        }

        if (npc.isUsingItem()) {
            if (!hasLineOfSight && seeTime < -60) {
                npc.stopUsingItem();
            } else if (hasLineOfSight) {
                int pullTime = npc.getTicksUsingItem();
                if (pullTime >= 20) {
                    npc.stopUsingItem();
                    npc.performRangedAttack(target, BowItem.getPowerForTime(pullTime));
                    attackTime = attackIntervalMin;
                }
            }
        } else if (--attackTime <= 0 && seeTime >= -60) {
            npc.startUsingItem(ProjectileUtil.getWeaponHoldingHand(npc, Items.BOW));
        }
    }
}
