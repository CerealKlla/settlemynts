package com.github.cerealklla.settlemynts.tunnel;

import java.util.UUID;

import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.registration.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * "Tunnel Stakes" -- the Mayor/Town-Planner placement tool for an in-settlement Tunnel shortcut.
 * Placing while anchored to an existing stake joins the two immediately (see {@code
 * TunnelStakeEntity#join}) and the anchor advances to the new stake, so further placements keep
 * extending the same group past 2 (widened 2026-11-12 from the original pair-only shape, per
 * explicit user spec -- see {@code TunnelStakeEntity}'s own doc); placing on bare ground with no
 * anchor just starts a fresh, unjoined stake and becomes the new anchor -- same open-chain shape as
 * {@code roadway.RoadwayStakeItem#useOn}, minus the paving/slope/plot-crossing checks that
 * mechanic needs and this one doesn't (a Tunnel group never touches real terrain).
 *
 * <p>Not consumed on placement, same reasoning as every other stake item in this mod.
 */
public class TunnelStakeItem extends Item {

    public TunnelStakeItem(Properties properties) {
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
        UUID ownerCoreId = held.get(ModItems.TUNNEL_STAKE_OWNER_CORE_ID);
        if (ownerCoreId == null) {
            player.sendSystemMessage(Component.literal("This stake isn't bound to a settlement -- get a fresh one from the Town Hall Core."));
            return InteractionResult.FAIL;
        }
        if (!(serverLevel.getEntity(ownerCoreId) instanceof GhostTownHallCoreEntity core) || !core.isTownPlanner(player.getUUID())) {
            player.sendSystemMessage(Component.literal("This stake's settlement is gone, or you're no longer a Town Planner there."));
            return InteractionResult.FAIL;
        }

        BlockPos placePos = context.getClickedPos().above();
        TunnelStakeEntity anchor = TunnelStakeLeash.resolveGhostAnchor(serverLevel, player.getUUID());

        TunnelStakeEntity newStake = TunnelStakeEntity.create(serverLevel, placePos.getX(), placePos.getY(), placePos.getZ(), ownerCoreId);
        if (anchor != null) {
            TunnelStakeEntity.join(anchor, newStake);
            // Anchor advances to the new stake -- mirrors roadway.RoadwayStakeItem's own open-chain
            // shape, so placing keeps extending the same group past 2.
            TunnelStakeLeash.setGhostAnchor(serverPlayer, newStake);
            player.sendSystemMessage(Component.literal(
                    "Joined -- place or right-click another stake to add it to this group too, "
                            + "or right-click any stake in the group (without a Tunnel Stake in hand) to finalize it into a real Tunnel, or to remove it."));
        } else {
            TunnelStakeLeash.setGhostAnchor(serverPlayer, newStake);
            player.sendSystemMessage(Component.literal(
                    "Anchored -- place or right-click another Tunnel Stake to join it to this one."));
        }
        return InteractionResult.SUCCESS_SERVER;
    }
}
