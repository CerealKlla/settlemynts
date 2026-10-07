package com.github.cerealklla.settlemynts.founding;

import java.util.List;
import java.util.UUID;

import com.github.cerealklla.settlemynts.registration.ModEntities;

import net.minecraft.core.UUIDUtil;
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
 * The Ghost Town Hall Core's visual marker -- a real, full-size {@code Blocks.BELL} block (a
 * vanilla {@link Display.BlockDisplay}, same technique and same per-Town-Planner {@code
 * broadcastToPlayer} visibility as every other ghost marker in this mod, e.g. {@code
 * GhostBoundaryWallEntity}), replacing the original floating {@code Items.BELL} *item icon* {@code
 * founding.client.GhostTownHallCoreRenderer} used to draw.
 *
 * <p><b>Correction, 2026-10-05</b>: an earlier same-day pass tried spawning ~85 of these (one per
 * cell of the "Town Hall Tier 1" reference Blueprint) to form a whole miniature building. That was
 * a real misunderstanding on the mod author's part, not the user's -- a Town Hall plot already gets
 * its own real Construction Box like any other plot, and a player can already select and build that
 * exact same Blueprint there as an actual, solid, permanently-placed structure, entirely independent
 * of this ghost marker. Spawning a second, non-solid copy of that structure around the Core produced
 * a confusing floating duplicate with no connection to wherever the real building actually got
 * built. Reverted to a single Bell block -- "the physical bell structure" was always meant to read
 * as "a real block, not a dropped-item-scale icon," not "the whole building."
 */
public class GhostTownHallBellEntity extends Display.BlockDisplay {

    private UUID ownerCoreId;

    public GhostTownHallBellEntity(EntityType<? extends GhostTownHallBellEntity> type, Level level) {
        super(type, level);
    }

    public static GhostTownHallBellEntity create(ServerLevel level, int x, int y, int z, UUID ownerCoreId) {
        GhostTownHallBellEntity bell = new GhostTownHallBellEntity(ModEntities.GHOST_TOWN_HALL_BELL.get(), level);
        bell.setPos(x, y, z);
        bell.ownerCoreId = ownerCoreId;
        GhostBlockDisplays.setBlockState(bell, Blocks.BELL.defaultBlockState());
        level.addFreshEntity(bell);
        return bell;
    }

    public UUID getOwnerCoreId() {
        return ownerCoreId;
    }

    /** The currently-loaded bell marker (if any) belonging to {@code coreId} -- used to discard it before respawning at a new position. Same bounded-search-box approach as {@code GhostBoundaryWallEntity#findByOwnerCore}. */
    public static List<GhostTownHallBellEntity> findByOwnerCore(ServerLevel level, UUID coreId, double x, double z) {
        double radius = 4;
        AABB searchBox = new AABB(
                x - radius, level.getMinY(), z - radius,
                x + radius, level.getMaxY(), z + radius);
        return level.getEntities(ModEntities.GHOST_TOWN_HALL_BELL.get(), searchBox, bell -> coreId.equals(bell.getOwnerCoreId()));
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
        return false; // Decorative only -- the Core entity itself is still the interactable one.
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Town Hall Core (marker)");
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
