package com.github.cerealklla.settlemynts.roadway;

import java.util.UUID;

import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.registration.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * "Roadway Stakes" (Roadways Milestone 1, added 2026-10-06) -- placement tool, Mayor/Town-Planner
 * gated, same "scroll away clears current anchor, right-click an existing post resumes from it"
 * mutable-anchor mechanic as {@code zone.PlotPlacementStakeItem}, but with no open-slot concept
 * (unbounded connections, see {@code RoadwayStakeEntity}'s own doc) and no plot-session id -- each
 * placed stake either starts a new, unconnected stake (no current anchor) or connects to whichever
 * post the player is currently anchored to. Placing while anchored both records that connection and
 * immediately paves the edge (see {@code RoadwayPaver}) -- not a batch Finalize step.
 *
 * <p>Not consumed on placement, same reasoning as every other stake item in this mod -- a Planner
 * should be able to keep placing several in a row without walking back for a fresh one each time.
 */
public class RoadwayStakeItem extends Item {

    public RoadwayStakeItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.SUCCESS;
        }
        Player player = context.getPlayer();
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.FAIL;
        }

        ItemStack held = context.getItemInHand();
        UUID ownerCoreId = held.get(ModItems.ROADWAY_STAKE_OWNER_CORE_ID);
        if (ownerCoreId == null) {
            player.sendSystemMessage(Component.literal("This stake isn't bound to a settlement -- get a fresh one from the Town Hall Core."));
            return InteractionResult.FAIL;
        }
        if (!(serverLevel.getEntity(ownerCoreId) instanceof GhostTownHallCoreEntity core) || !core.isTownPlanner(player.getUUID())) {
            player.sendSystemMessage(Component.literal("This stake's settlement is gone, or you're no longer a Town Planner there."));
            return InteractionResult.FAIL;
        }

        BlockPos placePos = context.getClickedPos().above();
        RoadwayStakeEntity anchor = RoadwayStakeLeash.resolveGhostAnchor(serverLevel, player.getUUID());

        if (anchor != null && RoadwayPaver.crossesPlot(serverLevel, core,
                Mth.floor(anchor.getX()), Mth.floor(anchor.getZ()), placePos.getX(), placePos.getZ())) {
            player.sendSystemMessage(Component.literal(
                    "A road can't cross directly through a plot -- route around it, or pick a different spot."));
            return InteractionResult.FAIL;
        }

        if (anchor != null) {
            int anchorGroundY = Mth.floor(anchor.getY()) - 1;
            int newGroundY = placePos.getY() - 1;
            if (RoadwayPaver.exceedsMaxSlope(Mth.floor(anchor.getX()), Mth.floor(anchor.getZ()), anchorGroundY,
                    placePos.getX(), placePos.getZ(), newGroundY)) {
                player.sendSystemMessage(Component.literal(
                        "That's too steep for a road -- place the stake closer, or pick a shallower route."));
                return InteractionResult.FAIL;
            }
        }

        RoadwayStakeEntity newPost = RoadwayStakeEntity.create(serverLevel, placePos.getX(), placePos.getY(), placePos.getZ(), ownerCoreId);
        if (anchor != null) {
            RoadwayStakeEntity.connect(anchor, newPost);
            RoadwayPaver.paveEdge(serverLevel, core, anchor, newPost);
        }
        RoadwayStakeLeash.setGhostAnchor(serverPlayer, newPost);
        return InteractionResult.SUCCESS_SERVER;
    }
}
