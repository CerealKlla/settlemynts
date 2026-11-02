package com.github.cerealklla.settlemynts.zone;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.founding.SettlementBoundingBox;
import com.github.cerealklla.settlemynts.founding.SettlementCoreIndex;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Real gap, 2026-11-02: vanilla's simulation distance is centered on each *player*, not on a
 * settlement, so a player standing well within a Murmur Tower's broadcast range (up to 244 blocks
 * at Tier 5) could easily be outside their own simulation radius of that settlement's chunks --
 * the tower's own {@code BlockEntity} (and everything else in the settlement) would simply be
 * unloaded, not just quiet. Explicit user spec: force-load every chunk belonging to a settlement
 * whenever any player is within 300 blocks of it, release once nobody remains in range.
 * Deliberately settlement-generic, not Murmur-Tower-specific -- framed that way by the user
 * themselves, and the same fix every other "should feel alive when a player's nearby" system
 * (NPCs, guards, crafting) benefits from too.
 *
 * <p><b>Level-triggered, not edge-triggered, by design</b> -- every scan independently re-derives
 * "is any currently online player within 300 blocks" from scratch off the live player list; it
 * never tracks a discrete "player entered range" event with a paired "player left range" release
 * to go find. A player who disconnects, crashes, gets kicked, or teleports away is simply absent
 * from the next scan's online-player list -- indistinguishable from having walked away, handled
 * identically by the grace-window logic below with zero extra code. Don't refactor this into an
 * event-paired enter/leave model later without re-solving that exact problem. The only other way a
 * claim could outlive its reason (a server crash mid-claim) is already covered by {@code
 * nuclyus.chunkload.ChunkClaims#validateTickets}'s own unconditional-discard-on-world-load policy.
 *
 * <p><b>Dimension safety</b>: {@code ServerLevel#players()} is already dimension-scoped for free --
 * it only ever returns players currently in that exact level, so comparing their distance to a
 * settlement's own core position (resolved for the matching dimension via {@code
 * SettlementCoreIndex}) never risks a cross-dimension coordinate collision.
 *
 * <p>Gated {@code ServerTickEvent.Post}, like every other periodic scan in this suite -- real work
 * only runs once every {@link #SCAN_INTERVAL_TICKS} ticks, not every tick. {@code TICK_OFFSET = 5}
 * confirmed free against the suite's other 100-tick-gated tickers (taken: 0, 12, 25, 37, 50, 62,
 * 75, 87) to avoid the documented "thundering herd" lag-spike incident those all guard against.
 */
public final class SettlementProximityTicker {

    private static final int SCAN_INTERVAL_TICKS = 100; // 5 real seconds at 20 TPS
    private static final int TICK_OFFSET = 5;
    private static final double RANGE_BLOCKS = 300.0;
    private static final long GRACE_TICKS = 600L; // 30 real seconds

    private final Map<UUID, Long> lastSeenInRangeTick = new HashMap<>();

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        int tickCount = server.getTickCount();
        if ((tickCount + TICK_OFFSET) % SCAN_INTERVAL_TICKS != 0) {
            return;
        }

        for (Map.Entry<UUID, GlobalPos> entry : SettlementCoreIndex.get(server).all().entrySet()) {
            UUID coreId = entry.getKey();
            GlobalPos corePos = entry.getValue();
            ServerLevel level = server.getLevel(corePos.dimension());
            if (level == null) {
                continue;
            }
            BlockPos coreBlockPos = corePos.pos();

            if (isAnyPlayerWithinRange(level, coreBlockPos)) {
                lastSeenInRangeTick.put(coreId, (long) tickCount);
                wakeSettlement(level, coreId, coreBlockPos);
            } else {
                Long lastSeen = lastSeenInRangeTick.get(coreId);
                if (lastSeen != null && tickCount - lastSeen > GRACE_TICKS) {
                    SettlementProximityWaker.sleep(level, coreId);
                    lastSeenInRangeTick.remove(coreId);
                }
            }
        }
    }

    private boolean isAnyPlayerWithinRange(ServerLevel level, BlockPos coreBlockPos) {
        double rangeSq = RANGE_BLOCKS * RANGE_BLOCKS;
        double x = coreBlockPos.getX() + 0.5;
        double y = coreBlockPos.getY() + 0.5;
        double z = coreBlockPos.getZ() + 0.5;
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(x, y, z) <= rangeSq) {
                return true;
            }
        }
        return false;
    }

    /** Two-step wake, mirroring {@code ShopMidnightRestockTicker#wakeAndPipelineSettlement} -- the plot list only exists on the live core entity, so its own chunk must be forced first before anything else can be resolved. */
    private void wakeSettlement(ServerLevel level, UUID coreId, BlockPos coreBlockPos) {
        SettlementProximityWaker.wake(level, coreId, List.of(coreBlockPos));
        if (!(level.getEntity(coreId) instanceof GhostTownHallCoreEntity core)) {
            return; // Shouldn't happen now that its own chunk is forced -- stay defensive, not fatal.
        }
        SettlementProximityWaker.wake(level, coreId, SettlementBoundingBox.resolveChunkRepresentatives(level, core));
    }
}
