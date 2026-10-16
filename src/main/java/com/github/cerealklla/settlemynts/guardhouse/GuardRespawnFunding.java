package com.github.cerealklla.settlemynts.guardhouse;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Container;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

/**
 * Guard Kit Stand overhaul (explicit user spec, 2026-10-16): "Every time a guard dies, consume an
 * amount of food... to reduce the respawn speed of the guards by 90%... The amount of food necessary
 * for the guard increases by Tier, from 10->50. The time for a guard to spawn is 5 minutes (clock
 * time, not server ticks) if there is no food present." Read as: 5 minutes is the no-food baseline;
 * successfully funding food cuts that down to 10% of 5 minutes (30 seconds) -- not "90% slower than
 * some other baseline."
 *
 * <p>{@link #FOOD_PER_TIER} mirrors {@code zone.NpcHireMaintenance#GOLD_PER_TIER}'s exact linear-by-
 * Tier shape (a single multiply) -- {@code 10 * tier} for Tier 1-5 already produces 10/20/30/40/50
 * verbatim, no new formula shape needed. "Clock time, not server ticks" just means real wall-clock
 * minutes/seconds, expressed the same way every other wall-clock duration in this suite already is
 * (a tick count at the normal 20 ticks/second rate, e.g. {@code combat.AssistTracker
 * #ASSIST_DURATION_TICKS}), not literally independent of the tick counter.
 */
public final class GuardRespawnFunding {

    private GuardRespawnFunding() {
    }

    private static final int FOOD_PER_TIER = 10;
    public static final long NO_FOOD_RESPAWN_TICKS = 5L * 60 * 20; // 5 real minutes.
    public static final long FUNDED_RESPAWN_TICKS = NO_FOOD_RESPAWN_TICKS / 10; // 90% faster -- 30 seconds.

    public static int foodValueRequiredForTier(int tier) {
        return FOOD_PER_TIER * Math.max(1, tier);
    }

    /**
     * Count-first-then-drain, all-or-nothing -- same shape as {@code zone.NpcHireMaintenance
     * #tryCharge}, adapted to drain by summed nutrition value (mirroring {@code
     * GuardhouseBlockEntity#totalFoodValue}'s own accounting) instead of a single fixed item type.
     * Drains whole food items/stacks at a time, which can overshoot the exact required value by up to
     * one item's own nutrition -- the same pragmatic greedy tolerance {@code zone.ContainerWithdraw}'s
     * own drain already accepts elsewhere, not worth a fractional-item-split for.
     */
    public static boolean tryFundFastRespawn(GuardhouseBlockEntity guardhouse, int tier) {
        int required = foodValueRequiredForTier(tier);
        if (guardhouse.totalFoodValue() < required) {
            return false;
        }
        drainFoodValue(guardhouse.foodContainer(), required);
        return true;
    }

    private static void drainFoodValue(Container container, int amount) {
        int remaining = amount;
        for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            FoodProperties food = stack.get(DataComponents.FOOD);
            if (food == null || food.nutrition() <= 0) {
                continue;
            }
            int perItem = food.nutrition();
            int itemsNeeded = (remaining + perItem - 1) / perItem; // Ceil -- take whole items.
            int take = Math.min(stack.getCount(), itemsNeeded);
            stack.shrink(take);
            remaining -= take * perItem;
        }
    }
}
