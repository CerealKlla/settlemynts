package com.github.cerealklla.settlemynts.roadway;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Per-player "which Highway Stake am I currently leashed to" tracking -- a copy of {@link
 * RoadwayStakeLeash}, typed to {@link HighwayStakeEntity} instead (added 2026-10-11, same
 * "duplicate small pure utility" precedent that class's own doc already describes).
 */
public final class HighwayStakeLeash {

    private static final Map<UUID, Integer> ghostAnchorByPlayer = new HashMap<>();

    private HighwayStakeLeash() {
    }

    public static void setGhostAnchor(ServerPlayer player, HighwayStakeEntity post) {
        ghostAnchorByPlayer.put(player.getUUID(), post.getId());
    }

    public static void clearGhostAnchor(UUID playerId) {
        ghostAnchorByPlayer.remove(playerId);
    }

    public static HighwayStakeEntity resolveGhostAnchor(ServerLevel level, UUID playerId) {
        Integer entityId = ghostAnchorByPlayer.get(playerId);
        if (entityId == null) {
            return null;
        }
        return level.getEntity(entityId) instanceof HighwayStakeEntity post ? post : null;
    }
}
