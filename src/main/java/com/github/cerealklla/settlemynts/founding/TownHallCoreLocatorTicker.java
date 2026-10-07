package com.github.cerealklla.settlemynts.founding;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Walk-away auto-cancel for a held Town Hall Core Locator -- same 10-tick, no-preview shape as
 * {@code plotsign.PlotConfigSignLocatorTicker}. This ticker's only job is calling {@link
 * TownHallCoreRelocatorItem#cancelIfHeld} every player, every interval.
 */
public final class TownHallCoreLocatorTicker {

    private static final int INTERVAL_TICKS = 10;

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % INTERVAL_TICKS != 0) {
            return;
        }
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            if (!(player.level() instanceof ServerLevel level)) {
                continue;
            }
            TownHallCoreRelocatorItem.cancelIfHeld(level, player, player.getMainHandItem(), false,
                    s -> player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY));
            TownHallCoreRelocatorItem.cancelIfHeld(level, player, player.getOffhandItem(), false,
                    s -> player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY));
        }
    }
}
