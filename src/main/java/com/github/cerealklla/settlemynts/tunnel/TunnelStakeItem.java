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
 * Placing while anchored to a still-unpaired stake pairs the two immediately (see {@code
 * TunnelStakeEntity#pair}); placing on bare ground with no anchor just starts a fresh, unpaired
 * stake and becomes the new anchor -- same shape as {@code roadway.RoadwayStakeItem#useOn}, minus
 * the paving/slope/plot-crossing checks that mechanic needs and this one doesn't (a Tunnel Stake
 * pair never touches real terrain).
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
            TunnelStakeEntity.pair(anchor, newStake);
            // Unlike Roadway Stakes' open chain, a pair is always exactly 2 -- nothing left to extend.
            TunnelStakeLeash.clearGhostAnchor(player.getUUID());
            player.sendSystemMessage(Component.literal(
                    "Paired -- right-click either stake (without a Tunnel Stake in hand) to finalize it into a real Tunnel, or to remove it."));
        } else {
            TunnelStakeLeash.setGhostAnchor(serverPlayer, newStake);
            player.sendSystemMessage(Component.literal(
                    "Anchored -- place or right-click a second Tunnel Stake to pair it with this one."));
        }
        return InteractionResult.SUCCESS_SERVER;
    }
}
