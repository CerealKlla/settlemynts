package com.github.cerealklla.settlemynts.roadway;

import java.util.Optional;
import java.util.UUID;

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
 * "Highway Stakes" -- a copy of {@link RoadwayStakeItem}, tweaked for inter-settlement roads
 * (added 2026-10-11, explicit user spec): **consumed one at a time on placement**, unlike the
 * original infinite tool ("These will be consumed like normal items would be, not infinite like
 * Roadway Stakes"). Granted as a finite stack when a road-connection quest is accepted (see
 * {@code bridge.MyssionsRoadQuestBridge} -- this mod has no Myssions dependency itself, the stack
 * is handed over from that side). Carries {@code HIGHWAY_STAKE_INSTANCE_ID} (always) and the two
 * optional settlement-core data components instead of a single {@code ownerCoreId}.
 */
public class HighwayStakeItem extends Item {

    public HighwayStakeItem(Properties properties) {
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
        UUID instanceId = held.get(ModItems.HIGHWAY_STAKE_INSTANCE_ID);
        if (instanceId == null) {
            player.sendSystemMessage(Component.literal("This stake isn't bound to a road quest."));
            return InteractionResult.FAIL;
        }
        UUID coreIdA = held.get(ModItems.HIGHWAY_STAKE_SETTLEMENT_CORE_A);
        UUID coreIdB = held.get(ModItems.HIGHWAY_STAKE_SETTLEMENT_CORE_B);

        BlockPos placePos = context.getClickedPos().above();
        HighwayStakeEntity anchor = HighwayStakeLeash.resolveGhostAnchor(serverLevel, player.getUUID());

        if (anchor != null) {
            var core = anchor.resolveNearestRealCore(serverLevel);
            if (HighwayPaver.crossesPlot(serverLevel, core,
                    Mth.floor(anchor.getX()), Mth.floor(anchor.getZ()), placePos.getX(), placePos.getZ())) {
                player.sendSystemMessage(Component.literal(
                        "A road can't cross directly through a plot -- route around it, or pick a different spot."));
                return InteractionResult.FAIL;
            }
            int anchorGroundY = Mth.floor(anchor.getY()) - 1;
            int newGroundY = placePos.getY() - 1;
            if (HighwayPaver.exceedsMaxSlope(Mth.floor(anchor.getX()), Mth.floor(anchor.getZ()), anchorGroundY,
                    placePos.getX(), placePos.getZ(), newGroundY)) {
                player.sendSystemMessage(Component.literal(
                        "That's too steep for a road -- place the stake closer, or pick a shallower route."));
                return InteractionResult.FAIL;
            }
        }

        HighwayStakeEntity newPost = HighwayStakeEntity.create(serverLevel, placePos.getX(), placePos.getY(), placePos.getZ(),
                player.getUUID(), instanceId, Optional.ofNullable(coreIdA), Optional.ofNullable(coreIdB));
        if (anchor != null) {
            HighwayStakeEntity.connect(anchor, newPost);
            HighwayPaver.paveEdge(serverLevel, anchor.resolveNearestRealCore(serverLevel), anchor, newPost);
            HighwayConnectionMarkerEntity.regenerateAll(serverLevel, player.getUUID(), instanceId, placePos);
        }
        HighwayStakeLeash.setGhostAnchor(serverPlayer, newPost);
        held.shrink(1); // Finite, unlike RoadwayStakeItem -- see this class's own doc.
        return InteractionResult.SUCCESS_SERVER;
    }
}
