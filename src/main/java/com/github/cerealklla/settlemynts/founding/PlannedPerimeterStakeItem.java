package com.github.cerealklla.settlemynts.founding;

import java.util.List;
import java.util.UUID;

import com.github.cerealklla.settlemynts.registration.ModEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * The Planned Perimeter Stake (design doc Section 6) -- obtained from a settlement's Ghost Town
 * Hall Core (see {@code GhostTownHallCoreEntity#interact}/{@code RequestPerimeterStakePayload}
 * handling in {@code SettlemyntsMod}), placed on the ground to mark a point on the settlement's
 * eventual perimeter.
 *
 * <p>Placement resolves "which settlement is this for" by finding the nearest {@code
 * GhostTownHallCoreEntity} the placer is a Town Planner of, within {@link
 * GhostPerimeterStakeEntity#MAX_PLACEMENT_RADIUS_BLOCKS} of that specific core -- not simply "the
 * nearest core," since a planner of a distant settlement could otherwise be blocked by an
 * unrelated nearby one they have no permission on.
 *
 * <p><b>Not consumed on placement</b> (2026-09-26 -- the item was originally shrunk by 1 per
 * placement, which turned out not to match the intent: a Planner should be able to keep
 * right-clicking with one stake in hand to place several in a row, without walking back to the
 * core for a fresh one each time).
 */
public class PlannedPerimeterStakeItem extends Item {

    public PlannedPerimeterStakeItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.SUCCESS;
        }
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.FAIL;
        }

        BlockPos placePos = context.getClickedPos().above();
        GhostTownHallCoreEntity ownerCore = findOwningCore(serverLevel, player.getUUID(), placePos);
        if (ownerCore == null) {
            player.sendSystemMessage(Component.literal(
                    "No settlement you're a Town Planner of has its Town Hall Core within "
                            + (int) GhostPerimeterStakeEntity.MAX_PLACEMENT_RADIUS_BLOCKS + " blocks (~"
                            + (int) GhostPerimeterStakeEntity.MAX_PLACEMENT_RADIUS_FEET + " feet) of here."));
            return InteractionResult.FAIL;
        }

        UUID activePlanner = ownerCore.getActivePerimeterPlanner();
        if (activePlanner != null && !activePlanner.equals(player.getUUID())) {
            player.sendSystemMessage(Component.literal("Someone else is already placing this settlement's perimeter stakes."));
            return InteractionResult.FAIL;
        }

        int existingStakes = GhostPerimeterStakeEntity.findByOwnerCore(serverLevel, ownerCore.getUUID()).size();
        if (existingStakes >= GhostPerimeterStakeEntity.MAX_STAKES_PER_SETTLEMENT) {
            player.sendSystemMessage(Component.literal(
                    "This settlement already has the maximum of " + GhostPerimeterStakeEntity.MAX_STAKES_PER_SETTLEMENT + " perimeter stakes."));
            return InteractionResult.FAIL;
        }

        ownerCore.setActivePerimeterPlanner(player.getUUID());
        // +1 block above the actual placement point (2026-09-27, playtest feedback) -- the live
        // fence-post preview (GhostPerimeterFencePostEntity) occupies this same (x,z) at ground
        // level as a real, solid block, which buried the stake's own marker; standing one block up
        // keeps it visible on top of the fence. The entity's *actual* position moves here (not just
        // a render offset), so the click/interact hitbox follows the visual -- a render-only offset
        // was a real bug (right-clicking where the torch visually appeared did nothing).
        GhostPerimeterStakeEntity.create(serverLevel, placePos.getX() + 0.5, placePos.getY() + 1, placePos.getZ() + 0.5, ownerCore.getUUID(), existingStakes);
        GhostPerimeterFencePostEntity.regenerate(serverLevel, ownerCore);
        return InteractionResult.SUCCESS_SERVER;
    }

    /** The nearest core the player may stake for, at or within the placement radius of {@code placePos} -- or {@code null} if none qualifies. */
    private static GhostTownHallCoreEntity findOwningCore(ServerLevel level, UUID playerId, BlockPos placePos) {
        double radius = GhostPerimeterStakeEntity.MAX_PLACEMENT_RADIUS_BLOCKS;
        AABB searchBox = new AABB(
                placePos.getX() - radius, level.getMinY(), placePos.getZ() - radius,
                placePos.getX() + radius, level.getMaxY(), placePos.getZ() + radius);
        List<GhostTownHallCoreEntity> nearbyCores = level.getEntities(ModEntities.GHOST_TOWN_HALL_CORE.get(), searchBox,
                core -> core.isTownPlanner(playerId));

        GhostTownHallCoreEntity nearest = null;
        double closestDistanceSq = Double.MAX_VALUE;
        for (GhostTownHallCoreEntity core : nearbyCores) {
            double dx = core.getX() - (placePos.getX() + 0.5);
            double dz = core.getZ() - (placePos.getZ() + 0.5);
            double distanceSq = dx * dx + dz * dz;
            if (distanceSq <= radius * radius && distanceSq < closestDistanceSq) {
                closestDistanceSq = distanceSq;
                nearest = core;
            }
        }
        return nearest;
    }
}
