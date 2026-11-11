package com.github.cerealklla.settlemynts.tunnel;

import java.util.List;
import java.util.UUID;

import com.github.cerealklla.cartographyr.geo.Geometry;
import com.github.cerealklla.settlemynts.founding.GhostBlockDisplays;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.registration.ModEntities;
import com.github.cerealklla.settlemynts.util.GroundHeightFinder;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;

/**
 * A lightweight ghost-marker trail along a Tunnel Stake pairing's own straight line, "showing a
 * rope between them" per the original design conversation -- deliberately NOT a real vanilla
 * {@code Leashable} rope ({@code TunnelStakeEntity}'s own doc explains why: a Tunnel's two stakes
 * can legitimately be far apart) and NOT {@code roadway.RoadwayConnectionMarkerEntity} reused
 * directly either -- that one is a {@code Pingable} with traveler-buff/tool-lighting machinery this
 * mechanic has no use for. Visible only to the owning settlement's Town Planners, same rule as the
 * stakes themselves; discarded outright the moment its pairing breaks (Remove or Finalize), never
 * regenerated/recomputed otherwise.
 */
public class TunnelConnectionMarkerEntity extends Display.BlockDisplay {

    private static final double SEARCH_RADIUS_BLOCKS = 500.0;
    private static final int MARKER_SPACING_BLOCKS = 4;

    private UUID ownerCoreId;
    private UUID tunnelId;

    public TunnelConnectionMarkerEntity(EntityType<? extends TunnelConnectionMarkerEntity> type, Level level) {
        super(type, level);
    }

    private static TunnelConnectionMarkerEntity create(ServerLevel level, int x, int y, int z, UUID ownerCoreId, UUID tunnelId) {
        TunnelConnectionMarkerEntity marker = new TunnelConnectionMarkerEntity(ModEntities.TUNNEL_CONNECTION_MARKER.get(), level);
        marker.setPos(x, y, z);
        marker.ownerCoreId = ownerCoreId;
        marker.tunnelId = tunnelId;
        marker.setInvulnerable(true);
        GhostBlockDisplays.setBlockState(marker, Blocks.SOUL_TORCH.defaultBlockState());
        level.addFreshEntity(marker);
        return marker;
    }

    static void spawnTrail(ServerLevel level, TunnelStakeEntity a, TunnelStakeEntity b, UUID tunnelId) {
        int x1 = Mth.floor(a.getX());
        int z1 = Mth.floor(a.getZ());
        int x2 = Mth.floor(b.getX());
        int z2 = Mth.floor(b.getZ());
        List<Geometry.Polygon.Vertex> line = Geometry.Polygon.supercoverLine(
                new Geometry.Polygon.Vertex(x1, z1), new Geometry.Polygon.Vertex(x2, z2));
        int referenceY = Mth.floor((a.getY() + b.getY()) / 2.0D);
        for (int i = 0; i < line.size(); i += MARKER_SPACING_BLOCKS) {
            Geometry.Polygon.Vertex cell = line.get(i);
            int markerY = GroundHeightFinder.findNear(level, cell.x(), cell.z(), referenceY);
            create(level, cell.x(), markerY, cell.z(), a.getOwnerCoreId(), tunnelId);
        }
    }

    static void removeTrail(ServerLevel level, UUID tunnelId) {
        AABB wholeLevel = new AABB(Double.NEGATIVE_INFINITY, level.getMinY(), Double.NEGATIVE_INFINITY,
                Double.POSITIVE_INFINITY, level.getMaxY(), Double.POSITIVE_INFINITY);
        for (TunnelConnectionMarkerEntity marker : level.getEntities(ModEntities.TUNNEL_CONNECTION_MARKER.get(),
                wholeLevel, m -> tunnelId.equals(m.tunnelId))) {
            marker.discard();
        }
    }

    private GhostTownHallCoreEntity findOwnerCore() {
        if (!(level() instanceof ServerLevel serverLevel) || ownerCoreId == null) {
            return null;
        }
        return serverLevel.getEntity(ownerCoreId) instanceof GhostTownHallCoreEntity core ? core : null;
    }

    @Override
    public boolean broadcastToPlayer(ServerPlayer player) {
        GhostTownHallCoreEntity core = findOwnerCore();
        return core != null && core.isTownPlanner(player.getUUID());
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Tunnel Connection Marker");
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        ownerCoreId = input.read("OwnerCoreId", UUIDUtil.CODEC).orElse(null);
        tunnelId = input.read("TunnelId", UUIDUtil.CODEC).orElse(null);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.storeNullable("OwnerCoreId", UUIDUtil.CODEC, ownerCoreId);
        output.storeNullable("TunnelId", UUIDUtil.CODEC, tunnelId);
    }
}
