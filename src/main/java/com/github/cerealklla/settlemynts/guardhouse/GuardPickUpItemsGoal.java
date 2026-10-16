package com.github.cerealklla.settlemynts.guardhouse;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import com.github.cerealklla.cartographyr.geo.Geometry;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/**
 * "Guards should be able to pick up loose items off the ground and immediately store them in the
 * storage boxes on the plot they are assigned to" (explicit user spec, 2026-10-16). Scoped to the
 * guard's own plot's "Town Proper" buffer polygon ({@link GuardEntity#resolvePatrolArea}, the same
 * area {@code GuardPatrolAreaGoal} already patrols) -- a guard never chases a loose item outside its
 * own jurisdiction. The actual deposit/auto-listing logic lives in {@link GuardLootDeposit}, kept
 * separate from this goal's own movement/timing, same split every other guard mechanic in this mod
 * uses ({@code GuardRespawnFunding} vs. {@link GuardEntity#die}).
 */
public class GuardPickUpItemsGoal extends Goal {

    private static final double SEARCH_RADIUS = 16.0;
    private static final double PICKUP_RANGE_SQ = 2.25; // 1.5 blocks.

    private final GuardEntity guard;
    private ItemEntity target;

    public GuardPickUpItemsGoal(GuardEntity guard) {
        this.guard = guard;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(guard.level() instanceof ServerLevel level)) {
            return false;
        }
        Optional<Geometry.Polygon> area = guard.resolvePatrolArea(level);
        if (area.isEmpty()) {
            return false;
        }
        List<ItemEntity> nearby = level.getEntitiesOfClass(ItemEntity.class, guard.getBoundingBox().inflate(SEARCH_RADIUS),
                item -> item.isAlive() && area.get().contains(Mth.floor(item.getX()), Mth.floor(item.getZ())));
        if (nearby.isEmpty()) {
            return false;
        }
        target = nearby.stream().min(Comparator.comparingDouble(guard::distanceToSqr)).orElse(null);
        return target != null;
    }

    @Override
    public boolean canContinueToUse() {
        return target != null && target.isAlive();
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
        guard.getLookControl().setLookAt(target.getX(), target.getY(), target.getZ());
        if (guard.distanceToSqr(target) <= PICKUP_RANGE_SQ) {
            pickUp();
            return;
        }
        guard.getNavigation().moveTo(target.getX(), target.getY(), target.getZ(), 1.0);
    }

    private void pickUp() {
        if (!(guard.level() instanceof ServerLevel level) || !target.isAlive()) {
            return;
        }
        ItemStack stack = target.getItem().copy();
        target.discard();
        GuardLootDeposit.deposit(level, guard.getSettlementCoreId(), guard.plotId(), stack);
        target = null;
    }
}
