package com.github.cerealklla.settlemynts.roadway;

import java.util.List;
import java.util.UUID;

import com.github.cerealklla.cartographyr.geo.Geometry;
import com.github.cerealklla.settlemynts.founding.GhostBlockDisplays;
import com.github.cerealklla.settlemynts.registration.ModEntities;

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
 * A copy of {@link RoadwayConnectionMarkerEntity}, keyed by a road quest's {@code instanceId}
 * instead of a settlement's {@code ownerCoreId}, and visible only to that instance's one player
 * (added 2026-10-11, same ghost-stakes-only-visible-to-the-questholder spec as {@link
 * HighwayStakeEntity}).
 */
public class HighwayConnectionMarkerEntity extends Display.BlockDisplay {

    private static final double SEARCH_RADIUS_BLOCKS = 2000.0;
    private static final int MARKER_SPACING_BLOCKS = 4;

    private UUID ownerPlayerId;
    private UUID linkedInstanceId;

    public HighwayConnectionMarkerEntity(EntityType<? extends HighwayConnectionMarkerEntity> type, Level level) {
        super(type, level);
    }

    private static HighwayConnectionMarkerEntity create(ServerLevel level, int x, int y, int z, UUID ownerPlayerId, UUID instanceId) {
        HighwayConnectionMarkerEntity marker = new HighwayConnectionMarkerEntity(ModEntities.HIGHWAY_CONNECTION_MARKER.get(), level);
        marker.setPos(x, y, z);
        marker.ownerPlayerId = ownerPlayerId;
        marker.linkedInstanceId = instanceId;
        GhostBlockDisplays.setBlockState(marker, Blocks.TORCH.defaultBlockState());
        level.addFreshEntity(marker);
        return marker;
    }

    private boolean selfCleaned = false;

    /**
     * Self-discards the moment this marker's chunk loads, if its instance was marked pending-hide --
     * added 2026-10-13, real live report: "the stakes didn't auto clear as I walked." Markers have no
     * persisted graph of their own (unlike {@link HighwayStakeEntity}), so this is the ONLY way a
     * marker outside render range at the moment a road completes ever finds out it should disappear --
     * see {@link HighwayStakeGraph#isPendingHide}'s own doc for why that flag deliberately never
     * auto-clears, specifically so a late-loading marker like this one can still see it.
     */
    @Override
    public void tick() {
        super.tick();
        if (!selfCleaned && linkedInstanceId != null && level() instanceof ServerLevel serverLevel
                && HighwayStakeGraph.get(serverLevel.getServer()).isPendingHide(linkedInstanceId)) {
            selfCleaned = true;
            discard();
        }
    }

    private static List<HighwayConnectionMarkerEntity> findByInstanceId(ServerLevel level, UUID instanceId, net.minecraft.core.BlockPos nearHint) {
        AABB searchBox = new AABB(
                nearHint.getX() - SEARCH_RADIUS_BLOCKS, level.getMinY(), nearHint.getZ() - SEARCH_RADIUS_BLOCKS,
                nearHint.getX() + SEARCH_RADIUS_BLOCKS, level.getMaxY(), nearHint.getZ() + SEARCH_RADIUS_BLOCKS);
        return level.getEntities(ModEntities.HIGHWAY_CONNECTION_MARKER.get(), searchBox, marker -> instanceId.equals(marker.linkedInstanceId));
    }

    /**
     * Discards every connection-marker torch for {@code instanceId} with nothing regenerated --
     * called on quest completion (explicit user request 2026-10-13: "all the ghost stakes and
     * torches on the finished road should be deleted/invisible again now that the road is done").
     * Purely cosmetic entities, no persisted graph of their own (unlike {@link HighwayStakeEntity}),
     * so this only reaches whatever's currently loaded -- an acceptable best-effort gap for a marker
     * with no gameplay effect, unlike the stake-terrain-revert risk that graph exists to prevent.
     */
    public static void discardAll(ServerLevel level, UUID instanceId, net.minecraft.core.BlockPos nearHint) {
        for (HighwayConnectionMarkerEntity existing : findByInstanceId(level, instanceId, nearHint)) {
            existing.discard();
        }
    }

    public static void regenerateAll(ServerLevel level, UUID ownerPlayerId, UUID instanceId, net.minecraft.core.BlockPos nearHint) {
        for (HighwayConnectionMarkerEntity existing : findByInstanceId(level, instanceId, nearHint)) {
            existing.discard();
        }
        for (HighwayStakeEntity stake : HighwayStakeEntity.findByInstanceId(level, instanceId, nearHint)) {
            for (UUID otherId : stake.getConnectionIds()) {
                if (stake.getUUID().compareTo(otherId) >= 0) {
                    continue;
                }
                if (!(level.getEntity(otherId) instanceof HighwayStakeEntity other)) {
                    continue;
                }
                int x1 = Mth.floor(stake.getX());
                int z1 = Mth.floor(stake.getZ());
                int x2 = Mth.floor(other.getX());
                int z2 = Mth.floor(other.getZ());
                List<Geometry.Polygon.Vertex> centerline = Geometry.Polygon.supercoverLine(
                        new Geometry.Polygon.Vertex(x1, z1), new Geometry.Polygon.Vertex(x2, z2));
                for (int i = 0; i < centerline.size(); i += MARKER_SPACING_BLOCKS) {
                    Geometry.Polygon.Vertex cell = centerline.get(i);
                    int markerY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cell.x(), cell.z());
                    create(level, cell.x(), markerY, cell.z(), ownerPlayerId, instanceId);
                }
            }
        }
    }

    @Override
    public boolean broadcastToPlayer(ServerPlayer player) {
        return ownerPlayerId != null && ownerPlayerId.equals(player.getUUID());
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Highway Connection Marker");
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        ownerPlayerId = input.read("OwnerPlayerId", UUIDUtil.CODEC).orElse(null);
        linkedInstanceId = input.read("LinkedInstanceId", UUIDUtil.CODEC).orElse(null);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.storeNullable("OwnerPlayerId", UUIDUtil.CODEC, ownerPlayerId);
        output.storeNullable("LinkedInstanceId", UUIDUtil.CODEC, linkedInstanceId);
    }
}
