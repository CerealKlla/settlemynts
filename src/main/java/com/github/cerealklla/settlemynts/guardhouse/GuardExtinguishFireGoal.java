package com.github.cerealklla.settlemynts.guardhouse;

import java.util.EnumSet;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * "Put out fires they can see within a certain distance when they reach a destination" (explicit
 * user spec, 2026-10-30, {@link #SEARCH_RADIUS_BLOCKS} = 15). A plain opportunistic goal, same
 * priority tier as {@link GuardGateOpenGoal}/{@link GuardPickUpItemsGoal} -- it only ever actually
 * gets a turn at the shared {@code Flag.MOVE} slot during the same idle windows
 * {@code GuardPatrolAreaGoal} already yields at (route just finished, about to re-pick), which is
 * what gives "when they reach a destination" its real effect without this goal needing to hook into
 * patrol arrival directly at all -- same established pattern this mod already uses for every other
 * "whatever a Guard notices along the way" behavior.
 *
 * <p><b>Literal line-of-sight scan</b>, per the user's own explicit spec -- not just "nearest fire
 * within radius." Mirrors vanilla's own {@code LivingEntity#hasLineOfSight(Entity)} exactly (same
 * {@link ClipContext.Block#COLLIDER}/{@link ClipContext.Fluid#NONE} shape, same
 * {@code HitResult.Type.MISS} success check, confirmed against the decompiled source), just aimed at
 * a block position's own center instead of another entity's eye position.
 *
 * <p>Throttled to once every {@link #SCAN_COOLDOWN_TICKS} -- a full radius-15 cube scan
 * (~29,791 block-state reads) is real, non-trivial work compared to this mod's other opportunistic
 * goals (an entity-AABB query, which is spatially indexed), so this runs far less often than
 * {@code GuardSightGoal}'s own 10-tick entity scan. Fed into {@code diagnostics.TickProfiler} like
 * every other per-Goal cost in this mod, so a real problem would show up in the same slow-tick
 * breakdown rather than going unnoticed.
 */
public class GuardExtinguishFireGoal extends Goal {

    private static final double SEARCH_RADIUS_BLOCKS = 15.0;
    private static final double EXTINGUISH_RANGE_SQ = 4.0; // 2 blocks.
    private static final int SCAN_COOLDOWN_TICKS = 40; // 2 seconds.

    private final GuardEntity guard;
    private BlockPos target;
    private int cooldown;

    public GuardExtinguishFireGoal(GuardEntity guard) {
        this.guard = guard;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return com.github.cerealklla.settlemynts.diagnostics.TickProfiler.timeValue(
                "GuardExtinguishFireGoal.canUse", this::canUseInternal);
    }

    private boolean canUseInternal() {
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        if (!(guard.level() instanceof ServerLevel level)) {
            return false;
        }
        cooldown = SCAN_COOLDOWN_TICKS;
        target = findNearestVisibleFire(level);
        return target != null;
    }

    @Override
    public boolean canContinueToUse() {
        return target != null && guard.level() instanceof ServerLevel level && isFire(level.getBlockState(target));
    }

    @Override
    public void stop() {
        target = null;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (target == null || !(guard.level() instanceof ServerLevel level)) {
            return;
        }
        Vec3 center = Vec3.atCenterOf(target);
        guard.getLookControl().setLookAt(center.x, center.y, center.z);
        if (guard.distanceToSqr(center) <= EXTINGUISH_RANGE_SQ) {
            extinguish(level);
            return;
        }
        guard.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0);
    }

    private void extinguish(ServerLevel level) {
        level.setBlock(target, Blocks.AIR.defaultBlockState(), 11);
        level.playSound(null, target, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 1.0F, 1.0F);
        level.gameEvent(guard, GameEvent.BLOCK_CHANGE, target);
        target = null;
    }

    private BlockPos findNearestVisibleFire(ServerLevel level) {
        BlockPos origin = guard.blockPosition();
        int radius = (int) Math.ceil(SEARCH_RADIUS_BLOCKS);
        double radiusSq = SEARCH_RADIUS_BLOCKS * SEARCH_RADIUS_BLOCKS;
        BlockPos nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-radius, -radius, -radius), origin.offset(radius, radius, radius))) {
            double distSq = guard.distanceToSqr(Vec3.atCenterOf(pos));
            if (distSq > radiusSq || distSq >= nearestDistSq) {
                continue;
            }
            if (!isFire(level.getBlockState(pos))) {
                continue;
            }
            if (!hasLineOfSight(level, pos)) {
                continue;
            }
            nearest = pos.immutable();
            nearestDistSq = distSq;
        }
        return nearest;
    }

    private static boolean isFire(BlockState state) {
        return state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE);
    }

    private boolean hasLineOfSight(ServerLevel level, BlockPos pos) {
        Vec3 from = new Vec3(guard.getX(), guard.getEyeY(), guard.getZ());
        Vec3 to = Vec3.atCenterOf(pos);
        return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, guard))
                .getType() == HitResult.Type.MISS;
    }
}
