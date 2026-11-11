package com.github.cerealklla.settlemynts.tunnel;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Per-player "which Tunnel Stake am I currently anchored to" tracking -- same small, separate,
 * server-only/transient map shape as {@code roadway.RoadwayStakeLeash}. Cleared on hand-switch (see
 * {@code SettlemyntsMod#onLeashTick}'s own Tunnel Stake branch) for the identical reason Plot Stakes'
 * own CurrentPlotID reset exists: without it, switching away and back could silently resume a stale
 * anchor and pair two stakes that were never meant to go together.
 */
public final class TunnelStakeLeash {

    private static final Map<UUID, Integer> ghostAnchorByPlayer = new HashMap<>();

    private TunnelStakeLeash() {
    }

    public static void setGhostAnchor(ServerPlayer player, TunnelStakeEntity stake) {
        ghostAnchorByPlayer.put(player.getUUID(), stake.getId());
    }

    public static void clearGhostAnchor(UUID playerId) {
        ghostAnchorByPlayer.remove(playerId);
    }

    /** Resolves to {@code null} once the anchor entity is no longer loaded -- self-healing, no explicit cleanup needed. */
    public static TunnelStakeEntity resolveGhostAnchor(ServerLevel level, UUID playerId) {
        Integer entityId = ghostAnchorByPlayer.get(playerId);
        if (entityId == null) {
            return null;
        }
        return level.getEntity(entityId) instanceof TunnelStakeEntity stake ? stake : null;
    }
}
