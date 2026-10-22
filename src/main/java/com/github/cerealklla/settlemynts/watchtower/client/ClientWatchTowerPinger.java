package com.github.cerealklla.settlemynts.watchtower.client;

import java.util.HashMap;
import java.util.Map;

import com.github.cerealklla.settlemynts.watchtower.WatchTowerRecomputePingPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Explicit user request, 2026-10-22: "leverage the pingable implementation for when a player is
 * near one of these torches to force it to recheck its own height" -- a grid column skipped during
 * {@code WatchTowerLightController#recompute} (unloaded chunk at the time) or left stale by terrain
 * changing underneath it only gets fixed on the Beacon's next lit/dark flip, which could be a long
 * time away for a continuously-fed tower. Rather than scan blocks/entities over a wide radius every
 * tick (the exact cost {@code plotsign.PlotShopProximityTicker}'s own 2026-10-12 rework moved away
 * from -- see its doc), this checks plain distance against the tiny, already-synced
 * {@link WatchTowerClientState#known()} list -- cheap enough to do every tick, throttled here purely
 * to avoid spamming the network while standing still near one.
 */
public final class ClientWatchTowerPinger {

    private static final int SCAN_INTERVAL_TICKS = 20; // 1s -- a terrain-staleness nicety, not urgent.
    private static final int PING_RADIUS_BLOCKS = 32;
    private static final double PING_RADIUS_SQ = (double) PING_RADIUS_BLOCKS * PING_RADIUS_BLOCKS;
    private static final int PING_COOLDOWN_TICKS = 200; // 10s -- don't re-ping the same Beacon every second while standing near it.

    private static int tickCounter = 0;
    private static final Map<GlobalPos, Integer> lastPingedAtTick = new HashMap<>();

    private ClientWatchTowerPinger() {
    }

    public static void tick() {
        tickCounter++;
        if (tickCounter % SCAN_INTERVAL_TICKS != 0) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || mc.getConnection() == null) {
            return;
        }
        ResourceKey<Level> dimension = level.dimension();

        for (GlobalPos beaconPos : WatchTowerClientState.known()) {
            if (!beaconPos.dimension().equals(dimension)
                    || player.blockPosition().distSqr(beaconPos.pos()) > PING_RADIUS_SQ) {
                continue;
            }
            Integer lastPinged = lastPingedAtTick.get(beaconPos);
            if (lastPinged != null && tickCounter - lastPinged < PING_COOLDOWN_TICKS) {
                continue;
            }
            lastPingedAtTick.put(beaconPos, tickCounter);
            mc.getConnection().send(new ServerboundCustomPayloadPacket(new WatchTowerRecomputePingPayload(beaconPos.pos())));
        }
    }
}
