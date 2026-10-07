package com.github.cerealklla.settlemynts.zone;

import java.util.List;
import java.util.UUID;

import com.github.cerealklla.cartographyr.geo.Geometry;
import com.github.cerealklla.settlemynts.founding.GhostBlockDisplays;
import com.github.cerealklla.settlemynts.registration.ModEntities;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;

/**
 * Ghost torch markers over every valid Road Access Flag placement spot (the plot's own live
 * perimeter, {@code PlotGeometry#perimeterCells}) -- shown while a Town Planner is holding a {@code
 * RoadAccessFlagItem} for a session that doesn't have a flag placed yet (explicit user request,
 * 2026-09-29: "as we're walking around with a road stake in hand we should make ghost torches to
 * show where valid placement can go"). Server-tick-driven ({@code SettlemyntsMod#onServerTick}),
 * not persisted -- purely a transient live preview, discarded outright once a flag is placed or the
 * item is no longer held, same "existence-gated, regenerated fresh" shape as {@code
 * GhostPlotFencePostEntity}.
 *
 * <p><b>Keyed by {@code viewerId} (the holding player), not session/core, since 2026-09-29</b> -- a
 * real double bug found live the same day: (1) {@code broadcastToPlayer} originally only checked
 * Town Planner membership, so any *other* online planner of the same settlement saw torches they
 * weren't holding the item for; the first fix for that (viewer-hand check added, still keyed by
 * session for lookup) didn't fully solve it either, because (2) {@code onServerTick} only ever called
 * {@link #regenerate} while a session was actively resolved -- the instant a player switched away
 * from the item, dropped it, or removed it from their hotbar, the tick loop just {@code continue}d
 * without ever clearing that player's already-spawned torches, so they stayed visible forever
 * ("I could even take them off the hotbar or drop the stake and they were still visible"). Keying
 * entirely by {@code viewerId} (found by player position, not by walking to an owning core entity)
 * means {@link #regenerate} can always be called unconditionally every tick -- including with an
 * empty cell list the moment the player stops holding the item -- with no session data required to
 * find and clear that player's own markers.
 */
public class GhostRoadAccessPreviewEntity extends Display.BlockDisplay {

    private static final double SEARCH_RADIUS_BLOCKS = 500.0;

    private UUID viewerId;

    public GhostRoadAccessPreviewEntity(EntityType<? extends GhostRoadAccessPreviewEntity> type, Level level) {
        super(type, level);
    }

    public static GhostRoadAccessPreviewEntity create(ServerLevel level, int x, int y, int z, UUID viewerId) {
        GhostRoadAccessPreviewEntity marker = new GhostRoadAccessPreviewEntity(ModEntities.GHOST_ROAD_ACCESS_PREVIEW.get(), level);
        marker.setPos(x, y, z);
        marker.viewerId = viewerId;
        GhostBlockDisplays.setBlockState(marker, Blocks.TORCH.defaultBlockState());
        level.addFreshEntity(marker);
        return marker;
    }

    public static List<GhostRoadAccessPreviewEntity> findByViewer(ServerLevel level, UUID viewerId) {
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(viewerId);
        if (player == null) {
            return List.of();
        }
        AABB searchBox = new AABB(
                player.getX() - SEARCH_RADIUS_BLOCKS, level.getMinY(), player.getZ() - SEARCH_RADIUS_BLOCKS,
                player.getX() + SEARCH_RADIUS_BLOCKS, level.getMaxY(), player.getZ() + SEARCH_RADIUS_BLOCKS);
        return level.getEntities(ModEntities.GHOST_ROAD_ACCESS_PREVIEW.get(), searchBox,
                marker -> viewerId.equals(marker.viewerId));
    }

    /** Discards every existing preview marker for this viewer, then rebuilds one per given cell -- an empty list just clears the preview, safe (and expected) to call unconditionally every tick. */
    public static void regenerate(ServerLevel level, UUID viewerId, List<Geometry.Polygon.Vertex> cells) {
        for (GhostRoadAccessPreviewEntity existing : findByViewer(level, viewerId)) {
            existing.discard();
        }
        for (Geometry.Polygon.Vertex cell : cells) {
            // MOTION_BLOCKING_NO_LEAVES, not WORLD_SURFACE -- same fix as PlotSitePlacement's own comment.
            int groundY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cell.x(), cell.z());
            create(level, cell.x(), groundY, cell.z(), viewerId);
        }
    }

    /** Visible only to the one player currently holding the Road Access Flag that generated it -- no broader Town Planner check needed, since {@link #regenerate} is itself already only ever called for the actual holder. */
    @Override
    public boolean broadcastToPlayer(ServerPlayer player) {
        return viewerId != null && viewerId.equals(player.getUUID());
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Road Access Placement Preview");
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        // Never actually persisted across a restart in practice -- discarded/regenerated every tick
        // while held -- but implemented for completeness/consistency with every other ghost marker.
        viewerId = input.read("ViewerId", net.minecraft.core.UUIDUtil.CODEC).orElse(null);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.storeNullable("ViewerId", net.minecraft.core.UUIDUtil.CODEC, viewerId);
    }
}
