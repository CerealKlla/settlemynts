package com.github.cerealklla.settlemynts.guardhouse;

import java.util.EnumSet;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;

/**
 * Replaces vanilla's flat-range {@code NearestAttackableTargetGoal} with a light-dependent sight
 * radius (explicit user spec, 2026-10-06): a guard spots a hostile {@link Monster} from {@link
 * #DARK_SIGHT_RANGE_BLOCKS} away if the monster's own position is dark, or {@link
 * #LIT_SIGHT_RANGE_BLOCKS} away if it's lit. "Lit" reuses {@link
 * net.minecraft.world.level.LevelReader#getMaxLocalRawBrightness}, the same effective-brightness
 * value (combined block + time-adjusted sky light) vanilla itself already uses to decide "safe
 * from hostile spawns," at the same {@link #LIT_LIGHT_THRESHOLD} vanilla uses for that -- so
 * daylight counts as "lit" just as much as a nearby torch, not just placed light sources.
 *
 * <p>Scans a flat {@link #MAX_SEARCH_RADIUS_BLOCKS}-sized box (the larger of the two ranges) every
 * {@link #COOLDOWN_TICKS}, picks the nearest qualifying, visible Monster, and assigns it directly
 * via {@link GuardEntity#setTarget} -- this goal never "runs" itself (no {@code start()}/{@code
 * stop()} behavior of its own), it only ever hands off to {@code MeleeAttackGoal} once a target is
 * set. Retaliating against whatever just hit the guard (regardless of range/light) stays a
 * completely separate, pre-existing mechanic via {@code HurtByTargetGoal}, untouched here.
 */
public class GuardSightGoal extends Goal {

    private static final double MAX_SEARCH_RADIUS_BLOCKS = 25.0;
    private static final double DARK_SIGHT_RANGE_BLOCKS = 15.0;
    private static final double LIT_SIGHT_RANGE_BLOCKS = 25.0;
    private static final int LIT_LIGHT_THRESHOLD = 8;
    private static final int COOLDOWN_TICKS = 10;

    private final GuardEntity guard;
    private int cooldown;

    public GuardSightGoal(GuardEntity guard) {
        this.guard = guard;
        setFlags(EnumSet.of(Flag.TARGET));
    }

    @Override
    public boolean canUse() {
        if (guard.getTarget() != null) {
            return false;
        }
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        cooldown = COOLDOWN_TICKS;
        if (!(guard.level() instanceof ServerLevel level)) {
            return false;
        }
        Monster found = findNearestVisibleMonster(level);
        if (found != null) {
            guard.setTarget(found);
        }
        return false;
    }

    private Monster findNearestVisibleMonster(ServerLevel level) {
        AABB searchBox = guard.getBoundingBox().inflate(MAX_SEARCH_RADIUS_BLOCKS);
        List<Monster> candidates = level.getEntitiesOfClass(Monster.class, searchBox, LivingEntity::isAlive);
        Monster nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (Monster candidate : candidates) {
            double distSq = guard.distanceToSqr(candidate);
            double range = sightRangeFor(level, candidate);
            if (distSq > range * range) {
                continue;
            }
            if (!guard.hasLineOfSight(candidate)) {
                continue;
            }
            if (distSq < nearestDistSq) {
                nearest = candidate;
                nearestDistSq = distSq;
            }
        }
        return nearest;
    }

    private static double sightRangeFor(ServerLevel level, Monster candidate) {
        BlockPos pos = candidate.blockPosition();
        int lightLevel = level.getMaxLocalRawBrightness(pos);
        return lightLevel >= LIT_LIGHT_THRESHOLD ? LIT_SIGHT_RANGE_BLOCKS : DARK_SIGHT_RANGE_BLOCKS;
    }
}
