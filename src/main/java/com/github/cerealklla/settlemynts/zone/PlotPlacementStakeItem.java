package com.github.cerealklla.settlemynts.zone;

import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.registration.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * A Plot Placement Stake (design doc Section 11a) -- placed to draw out a new plot's boundary
 * within an already-finalized settlement. Unlike {@code founding.PlannedPerimeterStakeItem}, which
 * resolves "which settlement" by proximity search at placement time, this item already carries
 * exactly which settlement and which in-progress plot it belongs to (see {@link PlotSessionData}),
 * embedded on the stack at grant time ("Get Plot Placement Stake") -- no search needed, and no
 * ambiguity if multiple settlements are nearby.
 *
 * <p>Not consumed on placement, same reasoning as the Perimeter Stake item -- a Planner should be
 * able to keep placing several plot stakes in a row without walking back for a fresh one each time.
 */
public class PlotPlacementStakeItem extends Item {

    public PlotPlacementStakeItem(Properties properties) {
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

        PlotSessionData session = context.getItemInHand().get(ModItems.PLOT_SESSION_DATA);
        if (session == null) {
            player.sendSystemMessage(Component.literal("This stake isn't bound to a plot -- get a fresh one from the Town Hall Core."));
            return InteractionResult.FAIL;
        }
        if (!(serverLevel.getEntity(session.ownerCoreId()) instanceof GhostTownHallCoreEntity core) || !core.isTownPlanner(player.getUUID())) {
            player.sendSystemMessage(Component.literal("This stake's settlement is gone, or you're no longer a Town Planner there."));
            return InteractionResult.FAIL;
        }

        BlockPos placePos = context.getClickedPos().above();
        int placementIndex = GhostPlotStakeEntity.findBySession(serverLevel, session.ownerCoreId(), session.plotSessionId()).size();
        // +1 block above the actual placement point, no +0.5 on x/z -- see
        // founding.PlannedPerimeterStakeItem's own comment (2026-09-27, same playtest feedback and
        // fix, shared verbatim).
        GhostPlotStakeEntity.create(serverLevel, placePos.getX(), placePos.getY() + 1, placePos.getZ(),
                session.ownerCoreId(), session.plotSessionId(), placementIndex);
        GhostPlotFencePostEntity.regenerate(serverLevel, session.ownerCoreId(), session.plotSessionId());
        return InteractionResult.SUCCESS_SERVER;
    }
}
