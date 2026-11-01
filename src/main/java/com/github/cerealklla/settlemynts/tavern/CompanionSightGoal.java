package com.github.cerealklla.settlemynts.tavern;

import java.util.EnumSet;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;

/**
 * A summoned Tavern Companion's own hostile-spotting goal -- explicit user spec, 2026-11-01: "Use
 * the same steps as town guards, e.g. ... look for enemies." Direct copy of {@code
 * guardhouse.GuardSightGoal} (light-dependent sight radius, "neutral until provoked" handling for
 * {@link NeutralMob}s, line-of-sight check), with exactly one addition: {@link #canUse} defers
 * entirely while {@link TavernNpcEntity#stoolPos()} is non-null, i.e. while this instance is still
 * the stationary, never-hired Tavern patron sitting on its stool -- a summoned companion (the only
 * case where {@code stoolPos} stays {@code null} forever, since {@link TavernNpcEntity#setIdentity}
 * is never called on one) is the only instance meant to ever go looking for a fight.
 */
public class CompanionSightGoal extends Goal {

    private static final double MAX_SEARCH_RADIUS_BLOCKS = 25.0;
    private static final double DARK_SIGHT_RANGE_BLOCKS = 15.0;
    private static final double LIT_SIGHT_RANGE_BLOCKS = 25.0;
    private static final int LIT_LIGHT_THRESHOLD = 8;
    private static final int COOLDOWN_TICKS = 10;

    private final TavernNpcEntity npc;
    private int cooldown;

    public CompanionSightGoal(TavernNpcEntity npc) {
        this.npc = npc;
        setFlags(EnumSet.of(Flag.TARGET));
    }

    @Override
    public boolean canUse() {
        if (npc.stoolPos() != null) {
            return false;
        }
        if (npc.getTarget() != null) {
            return false;
        }
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        cooldown = COOLDOWN_TICKS;
        if (!(npc.level() instanceof ServerLevel level)) {
            return false;
        }
        LivingEntity found = findNearestVisibleHostile(level);
        if (found != null) {
            npc.setTarget(found);
        }
        return false;
    }

    private LivingEntity findNearestVisibleHostile(ServerLevel level) {
        AABB searchBox = npc.getBoundingBox().inflate(MAX_SEARCH_RADIUS_BLOCKS);
        List<LivingEntity> candidates = level.getEntitiesOfClass(LivingEntity.class, searchBox,
                e -> e.isAlive() && e instanceof Enemy && isActivelyHostile(e));
        LivingEntity nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (LivingEntity candidate : candidates) {
            double distSq = npc.distanceToSqr(candidate);
            double range = sightRangeFor(level, candidate);
            if (distSq > range * range) {
                continue;
            }
            if (!npc.hasLineOfSight(candidate)) {
                continue;
            }
            if (distSq < nearestDistSq) {
                nearest = candidate;
                nearestDistSq = distSq;
            }
        }
        return nearest;
    }

    /** Same "neutral until provoked" default as {@code guardhouse.GuardSightGoal} -- see that class's own doc. */
    private static boolean isActivelyHostile(LivingEntity candidate) {
        return !(candidate instanceof NeutralMob neutral) || neutral.isAngry();
    }

    private static double sightRangeFor(ServerLevel level, LivingEntity candidate) {
        BlockPos pos = candidate.blockPosition();
        int lightLevel = level.getMaxLocalRawBrightness(pos);
        return lightLevel >= LIT_LIGHT_THRESHOLD ? LIT_SIGHT_RANGE_BLOCKS : DARK_SIGHT_RANGE_BLOCKS;
    }
}
