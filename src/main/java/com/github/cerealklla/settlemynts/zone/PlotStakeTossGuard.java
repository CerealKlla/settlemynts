package com.github.cerealklla.settlemynts.zone;

import com.github.cerealklla.settlemynts.registration.ModItems;

import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;

/**
 * Plot Stakes rework (2026-09-30, extended to the Road Access Flag the same day): "when dropping
 * the item, delete it from the player but don't drop a real item" -- both items are infinite-use and
 * meant to be re-requested/rebound cheaply, so there's no need to let either litter the world as a
 * pickup like a normal item. Simply cancelling {@link ItemTossEvent} is enough here, unlike
 * Yconomics' Coin Purse -- that item carries a balance that would otherwise be destroyed outright by
 * a bare cancel (its own doc explains why cancelling alone loses state); neither of these items has
 * state worth preserving once dropped (CurrentPlotID is reset on every hand-swap regardless, see
 * {@code SettlemyntsMod#onLeashTick}), so a bare cancel -- leaving it removed from the inventory,
 * never spawned into the world -- is exactly "deleted, no real item drop."
 */
public final class PlotStakeTossGuard {

    @SubscribeEvent
    public void onToss(ItemTossEvent event) {
        ItemStack tossed = event.getEntity().getItem();
        if (tossed.is(ModItems.PLOT_PLACEMENT_STAKE.get()) || tossed.is(ModItems.ROAD_ACCESS_FLAG.get())) {
            event.setCanceled(true);
        }
    }
}
