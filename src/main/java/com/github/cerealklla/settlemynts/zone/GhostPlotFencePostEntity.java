package com.github.cerealklla.settlemynts.zone;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.github.cerealklla.cartographyr.geo.Geometry;
import com.github.cerealklla.settlemynts.founding.FencePostConnections;
import com.github.cerealklla.settlemynts.founding.GhostBlockDisplays;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.registration.ModEntities;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;

/**
 * A live preview marker along a plot's in-progress boundary, one per block between
 * consecutively-placed stakes (design doc Section 11a, added 2026-09-27 -- see {@code
 * founding.GhostPerimeterFencePostEntity}'s doc for the full motivation, shared verbatim between
 * settlement and plot staking, including the real block-model {@link Display.BlockDisplay}
 * technique and connected-fence {@link FencePostConnections} logic). Scoped by {@link
 * #plotSessionId}, not just the owning core, since different Town Planners can each be mid-staking
 * their own separate plot at once. Discarded outright (not regenerated) once a plot is finalized.
 */
public class GhostPlotFencePostEntity extends Display.BlockDisplay {

    private static final double SEARCH_RADIUS_BLOCKS = 500.0;

    private UUID ownerCoreId;
    private UUID plotSessionId;

    public GhostPlotFencePostEntity(EntityType<? extends GhostPlotFencePostEntity> type, Level level) {
        super(type, level);
    }

    public static GhostPlotFencePostEntity create(ServerLevel level, int x, int y, int z, UUID ownerCoreId, UUID plotSessionId, BlockState state) {
        GhostPlotFencePostEntity post = new GhostPlotFencePostEntity(ModEntities.GHOST_PLOT_FENCE_POST.get(), level);
        post.setPos(x, y, z);
        post.ownerCoreId = ownerCoreId;
        post.plotSessionId = plotSessionId;
        GhostBlockDisplays.setBlockState(level, post, state);
        level.addFreshEntity(post);
        return post;
    }

    public UUID getOwnerCoreId() {
        return ownerCoreId;
    }

    public UUID getPlotSessionId() {
        return plotSessionId;
    }

    /** Every currently-loaded preview post belonging to one specific in-progress plot. */
    public static List<GhostPlotFencePostEntity> findBySession(ServerLevel level, UUID coreId, UUID plotSessionId) {
        Entity core = level.getEntity(coreId);
        if (core == null) {
            return List.of();
        }
        AABB searchBox = new AABB(
                core.getX() - SEARCH_RADIUS_BLOCKS, level.getMinY(), core.getZ() - SEARCH_RADIUS_BLOCKS,
                core.getX() + SEARCH_RADIUS_BLOCKS, level.getMaxY(), core.getZ() + SEARCH_RADIUS_BLOCKS);
        return level.getEntities(ModEntities.GHOST_PLOT_FENCE_POST.get(), searchBox,
                post -> plotSessionId.equals(post.getPlotSessionId()));
    }

    /**
     * Discards every existing preview post for this session, then rebuilds it from the session's
     * current stakes in placement order -- an open path (no closing edge), same technique as {@code
     * founding.GhostPerimeterFencePostEntity#regenerate}.
     */
    public static void regenerate(ServerLevel level, UUID ownerCoreId, UUID plotSessionId) {
        for (GhostPlotFencePostEntity existing : findBySession(level, ownerCoreId, plotSessionId)) {
            existing.discard();
        }
        List<GhostPlotStakeEntity> stakes = GhostPlotStakeEntity.findBySessionInPlacementOrder(level, ownerCoreId, plotSessionId);
        if (stakes.size() < 2) {
            return;
        }
        List<Geometry.Polygon.Vertex> blockPath = new ArrayList<>();
        for (int i = 0; i < stakes.size() - 1; i++) {
            Geometry.Polygon.Vertex a = new Geometry.Polygon.Vertex((int) Math.floor(stakes.get(i).getX()), (int) Math.floor(stakes.get(i).getZ()));
            Geometry.Polygon.Vertex b = new Geometry.Polygon.Vertex((int) Math.floor(stakes.get(i + 1).getX()), (int) Math.floor(stakes.get(i + 1).getZ()));
            blockPath.addAll(Geometry.Polygon.supercoverLine(a, b));
        }
        FencePostConnections.spawnConnected(level, blockPath,
                (lvl, x, y, z, state) -> create(lvl, x, y, z, ownerCoreId, plotSessionId, state));
    }

    @Override
    public boolean broadcastToPlayer(ServerPlayer player) {
        if (!(level() instanceof ServerLevel serverLevel) || ownerCoreId == null) {
            return false;
        }
        return serverLevel.getEntity(ownerCoreId) instanceof GhostTownHallCoreEntity core && core.isTownPlanner(player.getUUID());
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Plot Preview");
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        ownerCoreId = input.read("OwnerCoreId", UUIDUtil.CODEC).orElse(null);
        plotSessionId = input.read("PlotSessionId", UUIDUtil.CODEC).orElse(null);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.storeNullable("OwnerCoreId", UUIDUtil.CODEC, ownerCoreId);
        output.storeNullable("PlotSessionId", UUIDUtil.CODEC, plotSessionId);
    }
}
