package com.github.cerealklla.settlemynts.zone;

import java.util.List;
import java.util.UUID;

import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.registration.ModEntities;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The single live "carrying" rope preview -- a real vanilla {@link Leashable} connection (see {@link
 * com.github.cerealklla.settlemynts.rope.RopeAnchorEntity}'s own doc for why this needs a stand-in
 * entity rather than leashing the player directly: players aren't leashable in vanilla) trailing from
 * a player's current leash anchor to their own live position while they walk between posts. At most
 * one live instance per session -- {@link #regenerateCarryPreview} repositions the existing one
 * in-place (leash rendering recalculates every frame off live positions, so a moving marker reads as
 * a moving rope) rather than discarding/recreating it, rebuilt every ~10 ticks by {@code
 * SettlemyntsMod}'s tick handler.
 *
 * <p><b>Rope Fence rework, second pass (see decisions.md, 2026-09-29)</b>: this class used to also
 * own the *permanent* rope segments between two placed posts (painted tripwire). Those already moved
 * to {@link com.github.cerealklla.settlemynts.rope.RopeAnchorEntity}'s real {@code Leashable}
 * rendering that same day. <b>Corrected 2026-09-30</b>: this class's own *transient* carry preview
 * was left on tripwire at the time ("cheap/ephemeral, never the part the user objected to"), but a
 * later playtest found it did in fact look bad -- "every rope needs to look like a rope. That
 * tripwire looks terrible." Rebuilt on the same real {@code Leashable} rendering as the permanent
 * ropes, so every segment in this mechanic now looks identical.
 */
public class GhostPlotFencePostEntity extends Display.BlockDisplay implements Leashable {

    private static final double SEARCH_RADIUS_BLOCKS = 500.0;

    private UUID ownerCoreId;
    private UUID plotSessionId;
    private LeashData leashData;

    public GhostPlotFencePostEntity(EntityType<? extends GhostPlotFencePostEntity> type, Level level) {
        super(type, level);
    }

    /** No block state set -- nothing renders except the leash line itself, same trick {@code RopeAnchorEntity} uses. */
    public static GhostPlotFencePostEntity create(ServerLevel level, double x, double y, double z, UUID ownerCoreId, UUID plotSessionId) {
        GhostPlotFencePostEntity post = new GhostPlotFencePostEntity(ModEntities.GHOST_PLOT_FENCE_POST.get(), level);
        post.setPos(x, y, z);
        post.ownerCoreId = ownerCoreId;
        post.plotSessionId = plotSessionId;
        level.addFreshEntity(post);
        return post;
    }

    @Override
    public LeashData getLeashData() {
        return leashData;
    }

    @Override
    public void setLeashData(LeashData leashData) {
        this.leashData = leashData;
    }

    @Override
    public void tick() {
        super.tick();
        if (level() instanceof ServerLevel serverLevel) {
            Leashable.tickLeash(serverLevel, this);
        }
    }

    // See GhostPlotStakeEntity's identical override for why this is needed.
    @Override
    public double leashSnapDistance() {
        return com.github.cerealklla.settlemynts.rope.RopeConnections.LEASH_SNAP_DISTANCE_OVERRIDE;
    }

    public UUID getOwnerCoreId() {
        return ownerCoreId;
    }

    public UUID getPlotSessionId() {
        return plotSessionId;
    }

    /** Every currently-loaded carry-preview cell belonging to one specific in-progress plot. */
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
     * Moves the session's single live carry-preview marker to {@code playerPos} (creating it, leashed
     * to {@code anchor}, if it doesn't exist yet) -- a real {@code Leashable} connection re-renders
     * every frame off both entities' live positions, so this alone is enough to make the rope visibly
     * follow the player. {@code anchor}/{@code playerPos} both null discards it (used at
     * removal/finalize, and by anyone with no active anchor). Also discards and recreates if the
     * marker's leash holder no longer matches {@code anchor} (switched to a different post).
     */
    public static void regenerateCarryPreview(ServerLevel level, UUID ownerCoreId, UUID plotSessionId, GhostPlotStakeEntity anchor, Vec3 playerPos) {
        List<GhostPlotFencePostEntity> existing = findBySession(level, ownerCoreId, plotSessionId);
        if (anchor == null || playerPos == null) {
            existing.forEach(GhostPlotFencePostEntity::discard);
            return;
        }
        GhostPlotFencePostEntity marker = existing.stream().findFirst().orElse(null);
        if (marker == null) {
            marker = create(level, playerPos.x(), playerPos.y(), playerPos.z(), ownerCoreId, plotSessionId);
            marker.setLeashedTo(anchor, true);
        } else {
            existing.stream().skip(1).forEach(GhostPlotFencePostEntity::discard); // Self-healing -- should never be more than one, but never leave a stray behind if there somehow is.
            if (marker.getLeashHolder() != anchor) {
                marker.setLeashedTo(anchor, true);
            }
            marker.setPos(playerPos.x(), playerPos.y(), playerPos.z());
        }
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
        this.readLeashData(input);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.storeNullable("OwnerCoreId", UUIDUtil.CODEC, ownerCoreId);
        output.storeNullable("PlotSessionId", UUIDUtil.CODEC, plotSessionId);
        LeashData data = getLeashData();
        if (data != null) {
            this.writeLeashData(output, data);
        }
    }
}
