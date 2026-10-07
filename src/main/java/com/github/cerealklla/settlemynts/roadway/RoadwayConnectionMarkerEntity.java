package com.github.cerealklla.settlemynts.roadway;

import java.util.List;
import java.util.UUID;

import com.github.cerealklla.cartographyr.geo.Geometry;
import com.github.cerealklla.settlemynts.founding.GhostBlockDisplays;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.registration.ModEntities;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;

/**
 * A ghost torch marker along a Roadway Stake edge's own centerline (added 2026-10-06, explicit
 * live request: "when you in Roadway Stake editing mode it's very obvious which stakes are
 * connected in the event that roads overlap or are close to each other" -- the paved road blocks
 * themselves give no visual indication of which specific edge/connection they belong to once two
 * roads run close together or cross). Spawned in a string along every edge at {@link
 * #MARKER_SPACING_BLOCKS} intervals, same "ghost preview markers along a line" shape as {@code
 * zone.GhostRoadAccessPreviewEntity}, but keyed by {@code ownerCoreId} (not by one specific
 * viewer) since this mirrors {@link RoadwayStakeEntity}'s own visibility -- any Town Planner who
 * can currently see the stakes should see the connection markers too.
 *
 * <p><b>Not persisted</b> -- {@link #regenerateAll} is cheap to call in full any time the graph
 * changes (a stake is placed, connected, or removed), so these are always rebuilt from scratch
 * rather than incrementally maintained, the same "discard everything, rebuild fresh" shape {@code
 * GhostRoadAccessPreviewEntity#regenerate} already uses.
 */
public class RoadwayConnectionMarkerEntity extends Display.BlockDisplay {

    private static final double SEARCH_RADIUS_BLOCKS = 500.0;
    private static final int MARKER_SPACING_BLOCKS = 4;

    private UUID ownerCoreId;

    public RoadwayConnectionMarkerEntity(EntityType<? extends RoadwayConnectionMarkerEntity> type, Level level) {
        super(type, level);
    }

    private static RoadwayConnectionMarkerEntity create(ServerLevel level, int x, int y, int z, UUID ownerCoreId) {
        RoadwayConnectionMarkerEntity marker = new RoadwayConnectionMarkerEntity(ModEntities.ROADWAY_CONNECTION_MARKER.get(), level);
        // Plain block-corner position, no +0.5 center offset -- unlike RoadwayStakeEntity (which
        // needs that offset PLUS a render-translation compensation to keep its INTERACTION hitbox
        // centered), this marker is never clicked (isPickable() false), so it should just follow
        // GhostRoadAccessPreviewEntity's own simpler convention. Real live bug, 2026-10-06: copying
        // the stake's position offset here without its matching translation compensation left the
        // torch model rendered visibly off-center within its own block.
        marker.setPos(x, y, z);
        marker.ownerCoreId = ownerCoreId;
        GhostBlockDisplays.setBlockState(marker, Blocks.TORCH.defaultBlockState());
        level.addFreshEntity(marker);
        return marker;
    }

    private static List<RoadwayConnectionMarkerEntity> findByOwnerCore(ServerLevel level, UUID coreId) {
        Entity core = level.getEntity(coreId);
        if (core == null) {
            return List.of();
        }
        AABB searchBox = new AABB(
                core.getX() - SEARCH_RADIUS_BLOCKS, level.getMinY(), core.getZ() - SEARCH_RADIUS_BLOCKS,
                core.getX() + SEARCH_RADIUS_BLOCKS, level.getMaxY(), core.getZ() + SEARCH_RADIUS_BLOCKS);
        return level.getEntities(ModEntities.ROADWAY_CONNECTION_MARKER.get(), searchBox, marker -> coreId.equals(marker.ownerCoreId));
    }

    /**
     * Discards every existing marker for this settlement, then rebuilds one string of torches per
     * currently-existing connection -- called after any placement, direct connection, or removal
     * changes the graph. Since connections are now a plain symmetric graph (no parent/child, see
     * {@link RoadwayStakeEntity}'s own doc), each stake's own connection list would otherwise walk
     * every edge twice (once from each end) -- a connection is only ever drawn once here, from
     * whichever of its two stakes has the lexicographically smaller UUID, an arbitrary but stable
     * tie-break that needs no extra bookkeeping. Cheap even for a sizeable road network: the search
     * itself is bounded by {@link RoadwayStakeEntity#findByOwnerCore}'s own radius, and marker count
     * per edge is capped by the same {@link RoadwayStakeEntity#MAX_CONNECTION_LENGTH_BLOCKS} that
     * already bounds edge length.
     */
    public static void regenerateAll(ServerLevel level, UUID ownerCoreId) {
        for (RoadwayConnectionMarkerEntity existing : findByOwnerCore(level, ownerCoreId)) {
            existing.discard();
        }
        for (RoadwayStakeEntity stake : RoadwayStakeEntity.findByOwnerCore(level, ownerCoreId)) {
            for (UUID otherId : stake.getConnectionIds()) {
                if (stake.getUUID().compareTo(otherId) >= 0) {
                    continue; // Drawn once from the other end instead.
                }
                if (!(level.getEntity(otherId) instanceof RoadwayStakeEntity other)) {
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
                    create(level, cell.x(), markerY, cell.z(), ownerCoreId);
                }
            }
        }
    }

    /** Same visibility rule as {@link RoadwayStakeEntity} itself -- these markers should only ever be as visible as the stakes they're explaining. */
    @Override
    public boolean broadcastToPlayer(ServerPlayer player) {
        if (!(level() instanceof ServerLevel serverLevel) || ownerCoreId == null) {
            return false;
        }
        if (!(serverLevel.getEntity(ownerCoreId) instanceof GhostTownHallCoreEntity core) || !core.isTownPlanner(player.getUUID())) {
            return false;
        }
        if (core.isShowRoadwayStakes()) {
            return true;
        }
        var held = player.getMainHandItem();
        return held.is(com.github.cerealklla.settlemynts.registration.ModItems.ROADWAY_STAKE.get())
                && ownerCoreId.equals(held.get(com.github.cerealklla.settlemynts.registration.ModItems.ROADWAY_STAKE_OWNER_CORE_ID));
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Roadway Connection Marker");
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        // Never actually persisted in practice -- regenerated fresh by regenerateAll whenever the
        // graph changes -- but implemented for completeness/consistency with every other ghost marker.
        ownerCoreId = input.read("OwnerCoreId", UUIDUtil.CODEC).orElse(null);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.storeNullable("OwnerCoreId", UUIDUtil.CODEC, ownerCoreId);
    }
}
