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
 * "Put out fires they can see within a certain distance when they reach a destination, let's say
 * 15 blocks. literal line of sight scan... priority 1 upon reaching a target, then looking for
 * enemies, then picking a new stake to head toward" (explicit user spec, 2026-10-30).
 *
 * <p><b>Event-driven, not polled</b> -- explicit correction: "there should be no tick for this.
 * Reaches goal, scan 1 time." This goal never scans on its own; {@link #scanForVisibleFire} is
 * called exactly once by {@code GuardPatrolAreaGoal}'s own arrival handling (every waypoint/final-
 * destination reached, the actual "reach a destination" moment), which stores the result on {@link
 * GuardEntity#setPendingFireTarget}. This goal's own {@link #canUse()} is then a trivial check of
 * that field -- no scanning cost at all on a tick where nothing changed.
 *
 * <p><b>Priority 1</b> -- strictly above {@code GuardReturnToDimensionGoal}/the weapon-or-spell
 * goal (priority 2, whose own internal order -- heal an ally under 50%, then an enemy, then heal-
 * top-off -- is unchanged) and {@code GuardPatrolAreaGoal} (priority 3, picking a new stake).
 * Vanilla's own {@code GoalSelector} priority-preemption rules enforce the whole "fire, then heal
 * critical, then enemies, then heal top-off, then new stake" chain for free: the instant {@link
 * GuardEntity#pendingFireTarget()} becomes non-null, this goal's higher priority lets it immediately
 * take over the shared {@code Flag.MOVE} slot from whatever was running.
 *
 * <p>A genuine line-of-sight raycast per candidate (not just radius), mirroring vanilla's own {@code
 * LivingEntity#hasLineOfSight(Entity)} exactly (same {@link ClipContext.Block#COLLIDER}/
 * {@link ClipContext.Fluid#NONE} shape, same {@code HitResult.Type.MISS} success check, confirmed
 * against the decompiled source), aimed at a block position's own center instead of another
 * entity's eye position.
 */
public class GuardExtinguishFireGoal extends Goal {

    private static final double SEARCH_RADIUS_BLOCKS = 15.0;
    private static final double EXTINGUISH_RANGE_SQ = 4.0; // 2 blocks.

    private final GuardEntity guard;
    private BlockPos target;

    public GuardExtinguishFireGoal(GuardEntity guard) {
        this.guard = guard;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        target = guard.pendingFireTarget();
        return target != null;
    }

    @Override
    public boolean canContinueToUse() {
        return target != null && guard.level() instanceof ServerLevel level && isFire(level.getBlockState(target));
    }

    @Override
    public void stop() {
        target = null;
        guard.setPendingFireTarget(null);
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
        guard.setPendingFireTarget(null);
    }

    /**
     * The one-shot scan -- called exactly once by {@code GuardPatrolAreaGoal} at the moment a guard
     * actually arrives at a waypoint, never polled. Returns the nearest {@link
     * net.minecraft.world.level.block.FireBlock}/soul-fire position within {@link
     * #SEARCH_RADIUS_BLOCKS} that {@code guard} has a genuine, unobstructed line of sight to, or
     * {@code null} if none.
     */
    static BlockPos scanForVisibleFire(GuardEntity guard, ServerLevel level) {
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
            if (!hasLineOfSight(guard, level, pos)) {
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

    private static boolean hasLineOfSight(GuardEntity guard, ServerLevel level, BlockPos pos) {
        Vec3 from = new Vec3(guard.getX(), guard.getEyeY(), guard.getZ());
        Vec3 to = Vec3.atCenterOf(pos);
        return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, guard))
                .getType() == HitResult.Type.MISS;
    }
}
