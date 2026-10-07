package com.github.cerealklla.settlemynts.rope;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.github.cerealklla.settlemynts.zone.GhostPlotStakeEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Per-player "which Rope Fence Post am I currently leashed to" tracking -- server-only, transient
 * (not persisted; a server restart or relog just drops the leash, same as dropping the item would).
 * Two parallel maps since a player can be leashed to either a real placed post ({@link
 * RopeFencePostBlock}, tracked by {@link BlockPos}) or a ghost plot-staking post ({@code
 * zone.GhostPlotStakeEntity}, tracked by its network entity id -- valid only while loaded, which is
 * always true for the lifetime of an active plot-staking session).
 *
 * <p>Set when a post is placed or an existing open post is right-clicked to resume from; read every
 * tick by {@code SettlemyntsMod}'s leash pull handler to physically constrain the player to within
 * {@link #MAX_ROPE_LENGTH_BLOCKS} of the anchor while the corresponding item is still held (a ghost
 * anchor while holding a Plot Placement Stake, a real anchor while holding a Rope Fence Post item) --
 * switching away from that item is what makes "the rope disappears," per the user's own spec.
 */
public final class RopeFenceLeash {

    public static final double MAX_ROPE_LENGTH_BLOCKS = RopeConnections.MAX_ROPE_LENGTH_BLOCKS;

    private static final Map<UUID, Integer> ghostAnchorByPlayer = new HashMap<>();
    private static final Map<UUID, BlockPos> realAnchorByPlayer = new HashMap<>();

    private RopeFenceLeash() {
    }

    public static void setGhostAnchor(ServerPlayer player, GhostPlotStakeEntity post) {
        ghostAnchorByPlayer.put(player.getUUID(), post.getId());
    }

    public static void clearGhostAnchor(UUID playerId) {
        ghostAnchorByPlayer.remove(playerId);
    }

    /** Resolves to {@code null} once the anchor entity is no longer loaded (discarded, e.g. removed or the plot finalized) -- self-healing, no explicit cleanup needed on discard. */
    public static GhostPlotStakeEntity resolveGhostAnchor(ServerLevel level, UUID playerId) {
        Integer entityId = ghostAnchorByPlayer.get(playerId);
        if (entityId == null) {
            return null;
        }
        return level.getEntity(entityId) instanceof GhostPlotStakeEntity post ? post : null;
    }

    public static void setRealAnchor(UUID playerId, BlockPos pos) {
        realAnchorByPlayer.put(playerId, pos);
    }

    public static void clearRealAnchor(UUID playerId) {
        realAnchorByPlayer.remove(playerId);
    }

    public static BlockPos resolveRealAnchor(UUID playerId) {
        return realAnchorByPlayer.get(playerId);
    }
}
