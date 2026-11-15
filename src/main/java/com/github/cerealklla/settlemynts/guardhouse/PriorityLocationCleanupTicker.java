package com.github.cerealklla.settlemynts.guardhouse;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Explicit user spec: auto-clear a {@link PriorityLocation} once no mobs remain in its area, checked
 * every 10 real seconds -- the one and only lifecycle/decay rule this mechanic has (see that
 * class's own doc). Same gated-interval shape as every other periodic scan in this suite.
 */
public final class PriorityLocationCleanupTicker {

    private static final int SCAN_INTERVAL_TICKS = 200; // 10 real seconds at 20 TPS, explicit user spec
    private static final int TICK_OFFSET = 150; // free against the suite's existing 100-tick-family offsets (0,5,12,25,37,50,62,75,87) -- different modulus family, but kept distinct regardless

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if ((server.getTickCount() + TICK_OFFSET) % SCAN_INTERVAL_TICKS != 0) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            PriorityLocationRegistry.cleanup(level);
        }
    }
}
