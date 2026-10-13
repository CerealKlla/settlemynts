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
        Integer tier = held.get(ModItems.HIGHWAY_STAKE_TIER);
        int highwayTier = tier != null ? tier : 1;

        BlockPos placePos = context.getClickedPos().above();

        // Real live-test report, 2026-10-13: a stake could be planted inside a settlement's own
        // territory -- Highway Stakes connect two settlements, they don't belong inside one. Checked
        // against ANY settlement at this point (see HighwayPaver#isInsideAnySettlement's own doc),
        // not just this quest's own two named endpoints -- a natural (NPC) village has no real core
        // entity at all, so the original endpoint-specific check (keyed off the item's own core-id
        // data components, always absent for a natural settlement) never caught placement inside one.
        if (HighwayPaver.isInsideAnySettlement(serverLevel, placePos.getX(), placePos.getZ())) {
            player.sendSystemMessage(Component.literal(
                    "Highway Stakes can only be placed outside the settlement -- start just past its edge."));
            return InteractionResult.FAIL;
        }

        HighwayStakeEntity anchor = HighwayStakeLeash.resolveGhostAnchor(serverLevel, player.getUUID());

        if (anchor != null) {
            // Deliberately no placement-time distance check against the anchor -- explicit user
            // request, 2026-10-13, matching Plot Stakes' own established reasoning exactly (see
            // zone.PlotPlacementStakeItem's own doc): the physical leash pull (SettlemyntsMod's tick
            // handler, now wired up for Highway Stakes too) is what keeps placement within
            // MAX_CONNECTION_LENGTH_BLOCKS; a separate hard gate here fights normal vanilla reach and
            // forces the player to face backward to place forward progress. This was Highway's own
            // deliberate original difference from Roadway Stakes (which never had this hard check at
            // all) -- the user corrected that choice directly: "don't block me from placing the stake
            // at the distance limit, just leave that limit on the rope."
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
                player.getUUID(), instanceId, Optional.ofNullable(coreIdA), Optional.ofNullable(coreIdB), highwayTier);
        if (anchor != null) {
            HighwayStakeEntity.connect(anchor, newPost);
            HighwayPaver.paveEdge(serverLevel, anchor.resolveNearestRealCore(serverLevel), highwayTier, anchor, newPost);
            HighwayConnectionMarkerEntity.regenerateAll(serverLevel, player.getUUID(), instanceId, placePos);
        }
        HighwayStakeLeash.setGhostAnchor(serverPlayer, newPost);
        held.shrink(1); // Finite, unlike RoadwayStakeItem -- see this class's own doc.
        return InteractionResult.SUCCESS_SERVER;
    }
}
