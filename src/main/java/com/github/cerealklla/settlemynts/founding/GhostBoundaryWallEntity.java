package com.github.cerealklla.settlemynts.founding;

import java.util.List;
import java.util.UUID;

import com.github.cerealklla.settlemynts.registration.ModEntities;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;

/**
 * One point along a settlement's "View Settlement Boundaries" wall (design doc Section 9) -- a
 * marker entity floating vanilla's own {@code Items.GLASS} (a real block-item, so it renders as an
 * actual 3D cube via the same item-in-world machinery every other ghost object here uses, not a
 * flat icon -- looks like a genuine glass block strung along the line). Positions come from {@link
 * BoundaryWallLayout}.
 *
 * <p><b>Existence-gated, not a separate visibility flag</b> -- these entities are only ever
 * created while a settlement's boundary display is toggled on ({@code
 * SettlemyntsMod#toggleBoundaryWall}), and discarded when toggled off. Visibility is still
 * permission-gated on top of that (only the owning core's Town Planners can see them at all, same
 * {@code broadcastToPlayer} mechanism as every other ghost entity), but there's no separate
 * "is the toggle on" check needed here -- if one of these entities exists, the toggle is on.
 *
 * <p>Explicitly noted in the design doc: this is a **different** boundary line than the one
 * Settlemynts sends to Cartographyr (design doc Section 8, not built yet) -- this one is always
 * whatever the *current* stake positions describe, live, not the finalized/padded polygon.
 */
public class GhostBoundaryWallEntity extends Entity {

    /**
     * How many blocks tall each wall column is (2026-09-26, playtest feedback: individual
     * floating blocks pinned to the core's own Y didn't read as a wall at all once terrain height
     * varied along the perimeter -- some floated well above the ground, others sat underground).
     * Each {@link BoundaryWallLayout} point now gets a short vertical stack anchored to that
     * point's own local ground height instead of a single block at a shared Y -- see {@code
     * SettlemyntsMod#setBoundaryVisible}.
     */
    public static final int WALL_HEIGHT_BLOCKS = 4;

    private UUID ownerCoreId;

    public GhostBoundaryWallEntity(EntityType<? extends GhostBoundaryWallEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    public static GhostBoundaryWallEntity create(ServerLevel level, double x, double y, double z, UUID ownerCoreId) {
        GhostBoundaryWallEntity wall = new GhostBoundaryWallEntity(ModEntities.GHOST_BOUNDARY_WALL.get(), level);
        wall.setPos(x, y, z);
        wall.ownerCoreId = ownerCoreId;
        level.addFreshEntity(wall);
        return wall;
    }

    public UUID getOwnerCoreId() {
        return ownerCoreId;
    }

    /** Every currently-loaded wall point belonging to {@code coreId} -- used to discard the whole wall when toggled off. Same bounded-search-box approach as {@code GhostPerimeterStakeEntity#findByOwnerCore}. */
    public static List<GhostBoundaryWallEntity> findByOwnerCore(ServerLevel level, UUID coreId) {
        Entity core = level.getEntity(coreId);
        if (core == null) {
            return List.of();
        }
        double radius = GhostPerimeterStakeEntity.MAX_PLACEMENT_RADIUS_BLOCKS + 16;
        AABB searchBox = new AABB(
                core.getX() - radius, level.getMinY(), core.getZ() - radius,
                core.getX() + radius, level.getMaxY(), core.getZ() + radius);
        return level.getEntities(ModEntities.GHOST_BOUNDARY_WALL.get(), searchBox, wall -> coreId.equals(wall.getOwnerCoreId()));
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
        return false; // Decorative only -- nothing to interact with, unlike the stakes/core.
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Settlement Boundary");
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        return false;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        // No synced data needed -- visibility is entirely server-side, and there's no interactive
        // state to display beyond the entity's own fixed position.
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        ownerCoreId = input.read("OwnerCoreId", UUIDUtil.CODEC).orElse(null);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        output.storeNullable("OwnerCoreId", UUIDUtil.CODEC, ownerCoreId);
    }
}
