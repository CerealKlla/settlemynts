package com.github.cerealklla.settlemynts.guardhouse;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;

/**
 * Opens (and later closes behind itself) a closed fence gate a guard is physically pressed against --
 * real live report, 2026-10-16: "the guards are not opening/closing doors/gates when they get stuck
 * in places." Real wooden doors are handled by vanilla's own {@link
 * net.minecraft.world.entity.ai.goal.OpenDoorGoal}/{@code PathNavigation#setCanOpenDoors} (see {@link
 * GuardEntity#registerGoals}) -- confirmed by reading the real decompiled source, those already
 * exist and just weren't wired up for this mod's own {@code GuardEntity}. **Fence gates have no
 * vanilla equivalent at all, for any mob** -- confirmed directly in {@code WalkNodeEvaluator}'s own
 * `getBlockPathType`: a closed fence gate always returns the same `PathType` as a solid fence/wall,
 * with no `canOpenDoors`-style escape hatch the way a `DoorBlock` has. That means a guard's
 * pathfinding will never deliberately *plan* a route through a closed gate (a real, accepted
 * limitation of this fix -- building that would mean a custom `WalkNodeEvaluator`, a much larger
 * change). What this goal *does* fix is the reported symptom directly: a guard that ends up pressed
 * against a closed gate (wandering near one, a path that assumed it was already open, etc.) no
 * longer sits stuck there forever -- it opens the gate itself and continues.
 *
 * <p>Mirrors {@code DoorInteractGoal}'s own real shape (confirmed against its decompiled source) --
 * same "track whether I've walked past it yet" dot-product check so the gate closes behind the guard
 * once it's clearly moved on, not the instant it's no longer physically touching it.
 */
public class GuardGateOpenGoal extends Goal {

    private final GuardEntity guard;
    private BlockPos gatePos;
    private boolean hasGate;
    private boolean passed;
    private float gateDirX;
    private float gateDirZ;

    public GuardGateOpenGoal(GuardEntity guard) {
        this.guard = guard;
    }

    // Temporary instrumentation (2026-10-21) -- see diagnostics.TickProfiler's own doc.
    @Override
    public boolean canUse() {
        return com.github.cerealklla.settlemynts.diagnostics.TickProfiler.timeValue("GuardGateOpenGoal.canUse", this::canUseInternal);
    }

    private boolean canUseInternal() {
        if (!guard.horizontalCollision) {
            return false;
        }
        BlockPos found = findAdjacentClosedGate();
        if (found == null) {
            return false;
        }
        gatePos = found;
        hasGate = true;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return !passed;
    }

    @Override
    public void start() {
        passed = false;
        gateDirX = (float) (gatePos.getX() + 0.5 - guard.getX());
        gateDirZ = (float) (gatePos.getZ() + 0.5 - guard.getZ());
        setOpen(true);
    }

    @Override
    public void stop() {
        setOpen(false);
        hasGate = false;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        float newDirX = (float) (gatePos.getX() + 0.5 - guard.getX());
        float newDirZ = (float) (gatePos.getZ() + 0.5 - guard.getZ());
        float dot = gateDirX * newDirX + gateDirZ * newDirZ;
        if (dot < 0.0F) {
            passed = true;
        }
    }

    private BlockPos findAdjacentClosedGate() {
        BlockPos base = guard.blockPosition();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = base.relative(direction);
            BlockState state = guard.level().getBlockState(candidate);
            if (state.getBlock() instanceof FenceGateBlock && !state.getValue(FenceGateBlock.OPEN)) {
                return candidate;
            }
        }
        return null;
    }

    private void setOpen(boolean open) {
        if (!hasGate) {
            return;
        }
        BlockState state = guard.level().getBlockState(gatePos);
        if (!(state.getBlock() instanceof FenceGateBlock gate)) {
            hasGate = false;
            return;
        }
        if (state.getValue(FenceGateBlock.OPEN) == open) {
            return;
        }
        guard.level().setBlock(gatePos, state.setValue(FenceGateBlock.OPEN, open), 10);
        guard.level().playSound(null, gatePos, open ? gate.openSound : gate.closeSound, SoundSource.BLOCKS, 1.0F,
                guard.level().getRandom().nextFloat() * 0.1F + 0.9F);
        guard.level().gameEvent(guard, open ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, gatePos);
    }
}
