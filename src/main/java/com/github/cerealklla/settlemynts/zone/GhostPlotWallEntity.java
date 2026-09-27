package com.github.cerealklla.settlemynts.zone;

import java.util.List;
import java.util.UUID;

import com.github.cerealklla.settlemynts.founding.GhostBlockDisplays;
import com.github.cerealklla.settlemynts.founding.GhostPerimeterStakeEntity;
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
 * One point along a finalized plot's "Show Plot Perimeters" wall (design doc Section 11a) -- a
 * real block-model marker (a vanilla {@link Display.BlockDisplay}, walk-through/non-solid by
 * default), same technique as {@code founding.GhostBoundaryWallEntity} (switched from a floating
 * held-item icon 2026-09-27, see decisions.md), but the block varies per instance: each plot's own
 * {@link ZoneType#wallBlock()}, so different plot types read as visibly different colors.
 *
 * <p>Existence-gated exactly like {@code GhostBoundaryWallEntity} -- only exists while "Show Plot
 * Perimeters" is toggled on, regenerated fresh from every finalized plot's Cartographyr-stored
 * polygon each time it's toggled on (never stale).
 */
public class GhostPlotWallEntity extends Display.BlockDisplay {

    /** Deliberately shorter than {@code founding.GhostBoundaryWallEntity#WALL_HEIGHT_BLOCKS} (10) -- a plot's own perimeter is a finer-grained marker than the settlement's outer wall, not meant to loom as tall. */
    public static final int WALL_HEIGHT_BLOCKS = 3;

    private UUID ownerCoreId;

    public GhostPlotWallEntity(EntityType<? extends GhostPlotWallEntity> type, Level level) {
        super(type, level);
    }

    public static GhostPlotWallEntity create(ServerLevel level, double x, double y, double z, UUID ownerCoreId, BlockState wallBlock) {
        GhostPlotWallEntity wall = new GhostPlotWallEntity(ModEntities.GHOST_PLOT_WALL.get(), level);
        wall.setPos(x, y, z);
        wall.ownerCoreId = ownerCoreId;
        GhostBlockDisplays.setBlockState(level, wall, wallBlock);
        level.addFreshEntity(wall);
        return wall;
    }

    /** Every currently-loaded plot-wall point belonging to {@code coreId} -- used to discard the whole display when toggled off. */
    public static List<GhostPlotWallEntity> findByOwnerCore(ServerLevel level, UUID coreId) {
        Entity core = level.getEntity(coreId);
        if (core == null) {
            return List.of();
        }
        double radius = GhostPerimeterStakeEntity.MAX_PLACEMENT_RADIUS_BLOCKS + 16;
        AABB searchBox = new AABB(
                core.getX() - radius, level.getMinY(), core.getZ() - radius,
                core.getX() + radius, level.getMaxY(), core.getZ() + radius);
        return level.getEntities(ModEntities.GHOST_PLOT_WALL.get(), searchBox, wall -> coreId.equals(wall.getOwnerCoreId()));
    }

    public UUID getOwnerCoreId() {
        return ownerCoreId;
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
        return Component.literal("Plot Perimeter");
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        ownerCoreId = input.read("OwnerCoreId", UUIDUtil.CODEC).orElse(null);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.storeNullable("OwnerCoreId", UUIDUtil.CODEC, ownerCoreId);
    }
}
