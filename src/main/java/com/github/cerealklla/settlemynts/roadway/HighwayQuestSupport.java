package com.github.cerealklla.settlemynts.roadway;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import com.github.cerealklla.cartographyr.api.Cartography;
import com.github.cerealklla.cartographyr.geo.EntityId;
import com.github.cerealklla.cartographyr.geo.GeographicEntity;
import com.github.cerealklla.cartographyr.geo.Geometry;
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

    // [HighwayAnchorDebug] -- temporary, chasing a real live report (2026-10-13): a road-connection
    // quest still won't auto-complete even with the last stake placed directly outside the
    // destination settlement. Logs the real distanceOutside for every stake against both
    // settlements, to see exactly why anchor resolution is or isn't matching. Remove once found/fixed.
    private static final Logger LOGGER = LogUtils.getLogger();

    // How close a stake must be to a settlement's own registered edge to count as "anchoring" the
    // road to it. A same-day attempt widened this 3 -> 20 on the unconfirmed guess that a natural
    // village's convex-hull polygon might extend further than its visible buildings -- an invented
    // requirement the user explicitly corrected, never having asked for it: "by that I mean the last
    // stake must be directly outside the settlement perimeter. 1 block, not 20." Set to the user's
    // own exact spec.
    private static final double PERIMETER_ANCHOR_SLACK_BLOCKS = 1.0;

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

    /**
     * Live progress check for an in-progress road-connection instance -- see this class's own doc.
     *
     * <p>Walks {@link HighwayStakeGraph}'s persisted, entity-liveness-independent record of stake
     * positions/connections rather than scanning live entities -- fixed 2026-10-13 after a real,
     * confident live report ("I placed every single stake, and they are all connected") that the old
     * live-entity-scan version flatly contradicted. Root cause: {@code
     * HighwayStakeEntity#findByInstanceId} only ever sees entities in currently loaded chunks; a road
     * spanning more than render distance will never have its whole placed-and-connected chain loaded
     * at once, so the old check could structurally never confirm a real, complete path -- no amount
     * of anchor-slack tuning (the dead end chased right before this) could have fixed that.
     */
    public static HighwayProgress checkProgress(ServerLevel level, UUID instanceId, BlockPos nearHint, long placeIdA, long placeIdB) {
        Optional<GeographicEntity> settlementA = Cartography.getEntity(level, new EntityId(placeIdA)).map(e -> SettlementPerimeter.resolveRealBoundary(level, e));
        Optional<GeographicEntity> settlementB = Cartography.getEntity(level, new EntityId(placeIdB)).map(e -> SettlementPerimeter.resolveRealBoundary(level, e));
        if (settlementA.isEmpty() || settlementB.isEmpty()) {
            return HighwayProgress.NONE;
        }
        HighwayStakeGraph graph = HighwayStakeGraph.get(level.getServer());
        List<HighwayStakeGraph.StakeRecord> stakes = graph.byInstance(instanceId);
        if (stakes.isEmpty()) {
            return HighwayProgress.NONE;
        }

        UUID anchorA = null;
        UUID anchorB = null;
        for (HighwayStakeGraph.StakeRecord stake : stakes) {
            BlockPos pos = stake.pos();
            // Logs the real edge distance the check now actually uses (added 2026-10-13, same bug
            // investigation) -- distanceOutside() alone was proven misleading (returns 0.0 for both
            // "on the edge" and "deep inside"), so printing it was no longer enough to see WHY a
            // stake does or doesn't anchor once isJustOutside switched to distanceToNearestEdge.
            double edgeA = settlementA.get().geometry() instanceof Geometry.Polygon pA ? pA.distanceToNearestEdge(pos.getX(), pos.getZ()) : Double.NaN;
            double edgeB = settlementB.get().geometry() instanceof Geometry.Polygon pB ? pB.distanceToNearestEdge(pos.getX(), pos.getZ()) : Double.NaN;
            LOGGER.info("[HighwayAnchorDebug] stake at {} -- edgeDistance(A)={} edgeDistance(B)={} (slack={})",
                    pos, edgeA, edgeB, PERIMETER_ANCHOR_SLACK_BLOCKS);
            if (anchorA == null && SettlementPerimeter.isJustOutside(settlementA.get(), pos, PERIMETER_ANCHOR_SLACK_BLOCKS)) {
                anchorA = stake.stakeId();
            }
            if (anchorB == null && SettlementPerimeter.isJustOutside(settlementB.get(), pos, PERIMETER_ANCHOR_SLACK_BLOCKS)) {
                anchorB = stake.stakeId();
            }
        }
        LOGGER.info("[HighwayAnchorDebug] {} stakes total, anchorA={} anchorB={}",
                stakes.size(), anchorA, anchorB);
        if (anchorA == null || anchorB == null) {
            return new HighwayProgress(0, false);
        }
        Optional<List<UUID>> path = graph.findPath(instanceId, anchorA, anchorB);
        LOGGER.info("[HighwayAnchorDebug] path={}", path.map(List::size).orElse(-1));
        if (path.isEmpty()) {
            return new HighwayProgress(0, false);
        }
        registerTunnelProtectionForPath(level, graph, path.get());
        return new HighwayProgress(path.get().size(), true);
    }

    /**
     * Registers real tunnel protection for every edge along a now-genuinely-completed path -- see
     * {@code HighwayPaver#registerTunnelProtection}'s own doc for why this replaced per-edge
     * registration at pave time (a real live report: terrain became permanently uneditable while the
     * road was still under construction, long before the two settlements were actually connected).
     * {@link HighwayTunnelRegistry}-side storage is keyed by each edge's own road entity id, so
     * calling this again for an already-registered edge (e.g. {@code checkProgress} re-confirming
     * "connected" for a few more ticks before the quest instance's own status flips away from
     * IN_PROGRESS) is a harmless, idempotent overwrite, not a duplicate. Reads entirely from the
     * persisted graph (2026-10-13), so a stake entity being unloaded no longer blocks this either.
     */
    private static void registerTunnelProtectionForPath(ServerLevel level, HighwayStakeGraph graph, List<UUID> path) {
        for (int i = 0; i + 1 < path.size(); i++) {
            UUID fromId = path.get(i);
            UUID toId = path.get(i + 1);
            Optional<EntityId> roadEntityId = graph.roadEntityIdBetween(fromId, toId);
            Optional<BlockPos> fromPos = graph.posOf(fromId);
            Optional<BlockPos> toPos = graph.posOf(toId);
            if (roadEntityId.isPresent() && fromPos.isPresent() && toPos.isPresent()) {
                HighwayPaver.registerTunnelProtection(level, roadEntityId.get(),
                        new net.minecraft.world.phys.Vec3(fromPos.get().getX() + 0.5, fromPos.get().getY(), fromPos.get().getZ() + 0.5),
                        new net.minecraft.world.phys.Vec3(toPos.get().getX() + 0.5, toPos.get().getY(), toPos.get().getZ() + 0.5));
            }
        }
    }
}
