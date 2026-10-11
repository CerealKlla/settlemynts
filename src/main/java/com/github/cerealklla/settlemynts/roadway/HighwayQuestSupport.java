package com.github.cerealklla.settlemynts.roadway;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.github.cerealklla.cartographyr.api.Cartography;
import com.github.cerealklla.cartographyr.geo.EntityId;
import com.github.cerealklla.cartographyr.geo.GeographicEntity;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.registration.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * The real implementation behind {@code api.Settlemynts}'s three new Highway-quest-support
 * methods (added 2026-10-11) -- kept out of that facade class itself (which should stay a thin
 * pass-through) since there's genuine logic here: resolving a settlement's live core (if any) from
 * its Cartographyr placeId, granting a correctly-tagged Highway Stake stack, and checking a quest
 * instance's live connection progress. Myssions calls these through {@code
 * bridge.SettlemyntsRoadQuestBridge} -- see that class's own doc.
 */
public final class HighwayQuestSupport {

    // How close a stake must be to a settlement's own registered edge to count as "anchoring" the
    // road to it -- matches HighwayStakeItem's own placement-time intent (first block outside the
    // perimeter), with a little slack for the stake's exact column vs. the polygon's own vertices.
    private static final double PERIMETER_ANCHOR_SLACK_BLOCKS = 3.0;

    private HighwayQuestSupport() {
    }

    public static Optional<UUID> findCoreIdForPlaceId(ServerLevel level, long placeId) {
        return GhostTownHallCoreEntity.findByCartographyrEntityId(level, placeId).map(core -> core.getUUID());
    }

    /** Builds and hands the player a fresh, correctly-tagged Highway Stake stack. Either core id may be absent (a natural settlement). */
    public static void grantHighwayStakes(ServerPlayer player, UUID instanceId, Optional<UUID> coreIdA, Optional<UUID> coreIdB, int tier, int count) {
        ItemStack stack = new ItemStack(ModItems.HIGHWAY_STAKE.get(), Math.max(1, count));
        stack.set(ModItems.HIGHWAY_STAKE_INSTANCE_ID, instanceId);
        coreIdA.ifPresent(id -> stack.set(ModItems.HIGHWAY_STAKE_SETTLEMENT_CORE_A, id));
        coreIdB.ifPresent(id -> stack.set(ModItems.HIGHWAY_STAKE_SETTLEMENT_CORE_B, id));
        stack.set(ModItems.HIGHWAY_STAKE_TIER, Math.max(1, tier));
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    /** Removes every remaining Highway Stake item tagged with {@code instanceId} from the player's inventory, returning how many were removed -- the "we will buy back any remaining Highway Stakes" payout step, called once the road completes. */
    public static int buybackUnusedStakes(ServerPlayer player, UUID instanceId) {
        int removed = 0;
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(ModItems.HIGHWAY_STAKE.get()) && instanceId.equals(stack.get(ModItems.HIGHWAY_STAKE_INSTANCE_ID))) {
                removed += stack.getCount();
                stack.setCount(0);
            }
        }
        return removed;
    }

    public record HighwayProgress(int stakeCount, boolean connected) {
        public static final HighwayProgress NONE = new HighwayProgress(0, false);
    }

    /** Live progress check for an in-progress road-connection instance -- see this class's own doc. */
    public static HighwayProgress checkProgress(ServerLevel level, UUID instanceId, BlockPos nearHint, long placeIdA, long placeIdB) {
        Optional<GeographicEntity> settlementA = Cartography.getEntity(level, new EntityId(placeIdA));
        Optional<GeographicEntity> settlementB = Cartography.getEntity(level, new EntityId(placeIdB));
        if (settlementA.isEmpty() || settlementB.isEmpty()) {
            return HighwayProgress.NONE;
        }
        List<HighwayStakeEntity> stakes = HighwayStakeEntity.findByInstanceId(level, instanceId, nearHint);
        if (stakes.isEmpty()) {
            return HighwayProgress.NONE;
        }

        HighwayStakeEntity anchorA = null;
        HighwayStakeEntity anchorB = null;
        for (HighwayStakeEntity stake : stakes) {
            BlockPos pos = stake.blockPosition();
            if (anchorA == null && SettlementPerimeter.isJustOutside(settlementA.get(), pos, PERIMETER_ANCHOR_SLACK_BLOCKS)) {
                anchorA = stake;
            }
            if (anchorB == null && SettlementPerimeter.isJustOutside(settlementB.get(), pos, PERIMETER_ANCHOR_SLACK_BLOCKS)) {
                anchorB = stake;
            }
        }
        if (anchorA == null || anchorB == null) {
            return new HighwayProgress(0, false);
        }
        List<HighwayStakeEntity> path = RoadwayGraph.shortestPath(stakes, anchorA, anchorB);
        return path.isEmpty() ? new HighwayProgress(0, false) : new HighwayProgress(path.size(), true);
    }
}
