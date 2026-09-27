package com.github.cerealklla.settlemynts.zone;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.registration.ModEntities;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * A Plot Placement Stake, placed while drawing out a new plot (design doc Section 11a). Same
 * shape as {@code founding.GhostPerimeterStakeEntity} -- visible only to the owning settlement's
 * Town Planners, looked up live from the core each time -- but grouped by {@link #plotSessionId}
 * instead of belonging to the settlement as a whole, since multiple Town Planners can each be
 * drawing out their own separate plot at the same time.
 */
public class GhostPlotStakeEntity extends Entity {

    // Generous search radius around the owning core -- a plot is expected to sit well within a
    // settlement's own perimeter, so this doesn't need PerimeterFit's much larger placement-radius
    // margin the way GhostPerimeterStakeEntity's own search does.
    private static final double SEARCH_RADIUS_BLOCKS = 500.0;

    private UUID ownerCoreId;
    private UUID plotSessionId;
    private int placementIndex;

    public GhostPlotStakeEntity(EntityType<? extends GhostPlotStakeEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    /**
     * {@code placementIndex} is this stake's position in the actual sequence it was placed in --
     * always {@code findBySession(...).size()} at the moment of creation (see {@code
     * PlotPlacementStakeItem}). Building the plot's polygon from stakes sorted by this index (not
     * angularly around a centroid) is what makes non-star-shaped plots -- an L, a C, anything -- work
     * at all (2026-09-27, see decisions.md same date). Removal is restricted to the highest currently-
     * alive index (enforced in {@code SettlemyntsMod}'s {@code RemovePlotStakePayload} handler), which
     * is what keeps this scheme gap-free without needing a separately persisted ever-incrementing
     * counter.
     */
    public static GhostPlotStakeEntity create(ServerLevel level, double x, double y, double z, UUID ownerCoreId, UUID plotSessionId, int placementIndex) {
        GhostPlotStakeEntity stake = new GhostPlotStakeEntity(ModEntities.GHOST_PLOT_STAKE.get(), level);
        stake.setPos(x, y, z);
        stake.ownerCoreId = ownerCoreId;
        stake.plotSessionId = plotSessionId;
        stake.placementIndex = placementIndex;
        level.addFreshEntity(stake);
        return stake;
    }

    public UUID getOwnerCoreId() {
        return ownerCoreId;
    }

    public UUID getPlotSessionId() {
        return plotSessionId;
    }

    public int getPlacementIndex() {
        return placementIndex;
    }

    /** Every currently-loaded stake belonging to one specific in-progress plot. */
    public static List<GhostPlotStakeEntity> findBySession(ServerLevel level, UUID coreId, UUID plotSessionId) {
        Entity core = level.getEntity(coreId);
        if (core == null) {
            return List.of();
        }
        AABB searchBox = new AABB(
                core.getX() - SEARCH_RADIUS_BLOCKS, level.getMinY(), core.getZ() - SEARCH_RADIUS_BLOCKS,
                core.getX() + SEARCH_RADIUS_BLOCKS, level.getMaxY(), core.getZ() + SEARCH_RADIUS_BLOCKS);
        return level.getEntities(ModEntities.GHOST_PLOT_STAKE.get(), searchBox,
                stake -> plotSessionId.equals(stake.getPlotSessionId()));
    }

    /** {@link #findBySession} sorted by {@link #getPlacementIndex()} -- the actual polygon vertex order. */
    public static List<GhostPlotStakeEntity> findBySessionInPlacementOrder(ServerLevel level, UUID coreId, UUID plotSessionId) {
        List<GhostPlotStakeEntity> stakes = new ArrayList<>(findBySession(level, coreId, plotSessionId));
        stakes.sort(Comparator.comparingInt(GhostPlotStakeEntity::getPlacementIndex));
        return stakes;
    }

    private GhostTownHallCoreEntity findOwnerCore() {
        if (!(level() instanceof ServerLevel serverLevel) || ownerCoreId == null) {
            return null;
        }
        return serverLevel.getEntity(ownerCoreId) instanceof GhostTownHallCoreEntity core ? core : null;
    }

    /** Visible only to the owning settlement's current Town Planners -- see class doc. */
    @Override
    public boolean broadcastToPlayer(ServerPlayer player) {
        GhostTownHallCoreEntity core = findOwnerCore();
        return core != null && core.isTownPlanner(player.getUUID());
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        if (level().isClientSide() || !(player instanceof ServerPlayer serverPlayer) || !(level() instanceof ServerLevel serverLevel)) {
            return InteractionResult.SUCCESS;
        }
        int stakeCount = ownerCoreId != null && plotSessionId != null
                ? findBySession(serverLevel, ownerCoreId, plotSessionId).size()
                : 0;
        PacketDistributor.sendToPlayer(serverPlayer, new OpenPlotStakeScreenPayload(getId(), stakeCount));
        return InteractionResult.SUCCESS;
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Plot Placement Stake");
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        return false;
    }

    /** Discards this entity -- no item handed back (the stake item is reusable/infinite, same reasoning as {@code founding.GhostPerimeterStakeEntity#remove}). */
    public void remove(Player player) {
        discard();
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        // No synced data needed -- visibility is entirely server-side, same as GhostPerimeterStakeEntity.
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        ownerCoreId = input.read("OwnerCoreId", UUIDUtil.CODEC).orElse(null);
        plotSessionId = input.read("PlotSessionId", UUIDUtil.CODEC).orElse(null);
        placementIndex = input.getIntOr("PlacementIndex", 0);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        output.storeNullable("OwnerCoreId", UUIDUtil.CODEC, ownerCoreId);
        output.storeNullable("PlotSessionId", UUIDUtil.CODEC, plotSessionId);
        output.putInt("PlacementIndex", placementIndex);
    }
}
