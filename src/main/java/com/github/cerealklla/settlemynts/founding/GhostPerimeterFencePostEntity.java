package com.github.cerealklla.settlemynts.founding;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.github.cerealklla.cartographyr.geo.Geometry;
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
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;

/**
 * A live preview marker along the settlement perimeter's in-progress boundary, one per block
 * between consecutively-placed stakes (design doc Section 6/7a, added 2026-09-27 per explicit user
 * request: "I'd like to have ghost fence posts on every block between the stakes to help the player
 * know exactly where their boundary is before finalizing"). Unlike {@link GhostBoundaryWallEntity}
 * (a toggleable, post-Finalize-or-pre display of the *closed* perimeter), this is always regenerated
 * from the current stakes' **open** path (no closing edge back to the first stake -- the shape isn't
 * finalized yet) every time a stake is placed or removed, and discarded outright (not regenerated)
 * once Finalize succeeds -- see {@code SettlemyntsMod}'s stake-placement/removal/finalize call
 * sites.
 */
public class GhostPerimeterFencePostEntity extends Entity {

    private UUID ownerCoreId;

    public GhostPerimeterFencePostEntity(EntityType<? extends GhostPerimeterFencePostEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    public static GhostPerimeterFencePostEntity create(ServerLevel level, double x, double y, double z, UUID ownerCoreId) {
        GhostPerimeterFencePostEntity post = new GhostPerimeterFencePostEntity(ModEntities.GHOST_PERIMETER_FENCE_POST.get(), level);
        post.setPos(x, y, z);
        post.ownerCoreId = ownerCoreId;
        level.addFreshEntity(post);
        return post;
    }

    public UUID getOwnerCoreId() {
        return ownerCoreId;
    }

    /** Every currently-loaded preview post belonging to {@code coreId} -- used to discard/regenerate the whole preview. */
    public static List<GhostPerimeterFencePostEntity> findByOwnerCore(ServerLevel level, UUID coreId) {
        Entity core = level.getEntity(coreId);
        if (core == null) {
            return List.of();
        }
        double radius = GhostPerimeterStakeEntity.MAX_PLACEMENT_RADIUS_BLOCKS + 16;
        AABB searchBox = new AABB(
                core.getX() - radius, level.getMinY(), core.getZ() - radius,
                core.getX() + radius, level.getMaxY(), core.getZ() + radius);
        return level.getEntities(ModEntities.GHOST_PERIMETER_FENCE_POST.get(), searchBox, post -> coreId.equals(post.getOwnerCoreId()));
    }

    /**
     * Discards every existing preview post for {@code core}, then rebuilds it from {@code core}'s
     * current perimeter stakes in placement order -- an **open** path (no closing edge back to the
     * first stake, since the shape isn't finalized yet), walked with Cartographyr's shared
     * supercover-line primitive so the preview never has a gap on a diagonal run. Fewer than 2
     * stakes leaves no posts at all -- nothing meaningful to preview yet.
     */
    public static void regenerate(ServerLevel level, GhostTownHallCoreEntity core) {
        for (GhostPerimeterFencePostEntity existing : findByOwnerCore(level, core.getUUID())) {
            existing.discard();
        }
        List<GhostPerimeterStakeEntity> stakes = GhostPerimeterStakeEntity.findByOwnerCoreInPlacementOrder(level, core.getUUID());
        if (stakes.size() < 2) {
            return;
        }
        List<Geometry.Polygon.Vertex> blockPath = new ArrayList<>();
        for (int i = 0; i < stakes.size() - 1; i++) {
            Geometry.Polygon.Vertex a = new Geometry.Polygon.Vertex((int) Math.floor(stakes.get(i).getX()), (int) Math.floor(stakes.get(i).getZ()));
            Geometry.Polygon.Vertex b = new Geometry.Polygon.Vertex((int) Math.floor(stakes.get(i + 1).getX()), (int) Math.floor(stakes.get(i + 1).getZ()));
            blockPath.addAll(Geometry.Polygon.supercoverLine(a, b));
        }
        for (Geometry.Polygon.Vertex block : blockPath) {
            int groundY = level.getHeight(Heightmap.Types.WORLD_SURFACE, block.x(), block.z());
            create(level, block.x() + 0.5, groundY, block.z() + 0.5, core.getUUID());
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
        return Component.literal("Perimeter Preview");
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        return false;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        // No synced data needed -- visibility is entirely server-side.
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
