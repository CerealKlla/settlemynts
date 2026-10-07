package com.github.cerealklla.settlemynts.roadway;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Per-player "which Roadway Stake am I currently leashed to" tracking -- a small, separate
 * duplicate of {@code rope.RopeFenceLeash}'s own ghost-anchor map, kept apart rather than
 * generalizing that class, since its map is typed specifically to {@code zone.GhostPlotStakeEntity}
 * and this mechanic's items/entities are otherwise independent (same "duplicate small pure
 * utility" precedent already used elsewhere in this mod). Server-only, transient -- a server
 * restart or relog just drops the leash, same as dropping the item would.
 */
public final class RoadwayStakeLeash {

    private static final Map<UUID, Integer> ghostAnchorByPlayer = new HashMap<>();

    private RoadwayStakeLeash() {
    }

    public static void setGhostAnchor(ServerPlayer player, RoadwayStakeEntity post) {
        ghostAnchorByPlayer.put(player.getUUID(), post.getId());
    }

    public static void clearGhostAnchor(UUID playerId) {
        ghostAnchorByPlayer.remove(playerId);
    }

    /** Resolves to {@code null} once the anchor entity is no longer loaded -- self-healing, no explicit cleanup needed. */
    public static RoadwayStakeEntity resolveGhostAnchor(ServerLevel level, UUID playerId) {
        Integer entityId = ghostAnchorByPlayer.get(playerId);
        if (entityId == null) {
            return null;
        }
        return level.getEntity(entityId) instanceof RoadwayStakeEntity post ? post : null;
    }
}
