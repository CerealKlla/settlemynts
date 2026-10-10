package com.github.cerealklla.settlemynts.guardhouse;

import java.util.EnumSet;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;

/**
 * Replaces vanilla's flat-range {@code NearestAttackableTargetGoal} with a light-dependent sight
 * radius (explicit user spec, 2026-10-06): a guard spots a hostile {@link Enemy} from {@link
 * #DARK_SIGHT_RANGE_BLOCKS} away if its position is dark, or {@link #LIT_SIGHT_RANGE_BLOCKS} away
 * if it's lit. "Lit" reuses {@link net.minecraft.world.level.LevelReader#getMaxLocalRawBrightness},
 * the same effective-brightness value (combined block + time-adjusted sky light) vanilla itself
 * already uses to decide "safe from hostile spawns," at the same {@link #LIT_LIGHT_THRESHOLD}
 * vanilla uses for that -- so daylight counts as "lit" just as much as a nearby torch, not just
 * placed light sources.
 *
 * <p><b>Real bug found and fixed, 2026-10-08</b> (live report: "guards use their bows against
 * ground enemies but are completely ignoring phantoms"): this originally filtered candidates by
 * {@code Monster.class}, vanilla's ground-hostile base class. Confirmed against the decompiled
 * source that flying hostiles are siblings, not subclasses, of {@code Monster} -- {@code Phantom
 * extends Mob implements Enemy} directly, never touching {@code Monster} at all, while {@code
 * Monster} itself is only {@code implements Enemy}. {@link Enemy} (a marker interface, not an
 * entity class) is the one thing every hostile mob actually shares, ground or flying, so the
 * candidate scan now filters a plain {@code LivingEntity} search by {@code instanceof Enemy}
 * instead of restricting the search itself to {@code Monster.class}.
 *
 * <p>Scans a flat {@link #MAX_SEARCH_RADIUS_BLOCKS}-sized box (the larger of the two ranges) every
 * {@link #COOLDOWN_TICKS}, picks the nearest qualifying, visible hostile, and assigns it directly
 * via {@link GuardEntity#setTarget} -- this goal never "runs" itself (no {@code start()}/{@code
 * stop()} behavior of its own), it only ever hands off to {@code MeleeAttackGoal}/{@code
 * RangedBowAttackGoal} once a target is set. Retaliating against whatever just hit the guard
 * (regardless of range/light) stays a completely separate, pre-existing mechanic via {@code
 * HurtByTargetGoal}, untouched here.
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
        // Temporary diagnostic (2026-10-10, see diagnostics.LagDiagnostics' own doc) -- remove once
        // the real lag cause is confirmed.
        long start = System.nanoTime();
        LivingEntity found = findNearestVisibleHostile(level);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        if (elapsedMs >= 5) {
            com.github.cerealklla.settlemynts.SettlemyntsMod.LOGGER.info("[LagDiagnostics] GuardSightGoal.findNearestVisibleHostile took {}ms", elapsedMs);
        }
        if (found != null) {
            guard.setTarget(found);
        }
        return false;
    }

    private LivingEntity findNearestVisibleHostile(ServerLevel level) {
        AABB searchBox = guard.getBoundingBox().inflate(MAX_SEARCH_RADIUS_BLOCKS);
        List<LivingEntity> candidates = level.getEntitiesOfClass(LivingEntity.class, searchBox,
                e -> e.isAlive() && e instanceof Enemy);
        LivingEntity nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (LivingEntity candidate : candidates) {
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

    private static double sightRangeFor(ServerLevel level, LivingEntity candidate) {
        BlockPos pos = candidate.blockPosition();
        int lightLevel = level.getMaxLocalRawBrightness(pos);
        return lightLevel >= LIT_LIGHT_THRESHOLD ? LIT_SIGHT_RANGE_BLOCKS : DARK_SIGHT_RANGE_BLOCKS;
    }
}
