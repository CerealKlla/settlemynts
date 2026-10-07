package com.github.cerealklla.settlemynts.plotsign;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Walk-away auto-cancel for a held Plot Config Sign Locator -- same 10-tick shape as Blueprynts'
 * {@code construction.SupplyBoxLocatorTicker}, but deliberately no ghost preview (unchanged scope
 * decision from the Plot Config Sign's own design: "no Ghost-indicator placement constraint... the
 * sign can go anywhere within the plot's own bounds"). This ticker's only job is calling {@link
 * PlotConfigSignRelocatorItem#cancelIfHeld} every player, every interval.
 */
public final class PlotConfigSignLocatorTicker {

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
            PlotConfigSignRelocatorItem.cancelIfHeld(level, player, player.getMainHandItem(), false,
                    s -> player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY));
            PlotConfigSignRelocatorItem.cancelIfHeld(level, player, player.getOffhandItem(), false,
                    s -> player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY));
        }
    }
}
