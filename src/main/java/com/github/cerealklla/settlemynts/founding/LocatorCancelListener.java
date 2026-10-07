package com.github.cerealklla.settlemynts.founding;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Dying or logging out while holding a bound Town Hall Core Locator auto-cancels the pending
 * reposition, same as walking outside the Town Hall Plot -- mirrors {@code
 * plotsign.LocatorCancelListener} exactly. Runs before vanilla's own death-drop handling ({@link
 * LivingDeathEvent} fires pre-drop), so a canceled Locator's stack is cleared from the inventory slot
 * and never also spawns as a separate dropped item in the world.
 */
public final class LocatorCancelListener {

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof Player player && player.level() instanceof ServerLevel level) {
            cancelBoth(level, player);
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof Player player && player.level() instanceof ServerLevel level) {
            cancelBoth(level, player);
        }
    }

    private void cancelBoth(ServerLevel level, Player player) {
        cancelHand(level, player, InteractionHand.MAIN_HAND);
        cancelHand(level, player, InteractionHand.OFF_HAND);
    }

    private void cancelHand(ServerLevel level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        TownHallCoreRelocatorItem.cancelIfHeld(level, player, stack, true, s -> player.setItemInHand(hand, ItemStack.EMPTY));
    }
}
