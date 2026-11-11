package com.github.cerealklla.settlemynts.tunnel;

import com.github.cerealklla.settlemynts.bridge.TinyWyrldsTunnelBridge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/**
 * Converts a paired {@link TunnelStakeEntity} pair into a real Tunnel door pair -- the Mayor/Town-
 * Planner-placed counterpart of {@code zone.NaturalSettlementTunnelPlacer}'s automatic one. Always
 * {@code WyrldLifecycle#PUBLIC} + a bare no-loot/no-mob recipe, same reasoning as that class
 * ("a town placing stakes might always be no loot, no mobs, public" -- explicit user spec).
 *
 * <p><b>Room count is dynamic, based on the real-world distance between the two stakes</b> (explicit
 * user spec, 2026-11-11: "dynamically determine the number of rooms based on distances between the
 * entrance stakes, maybe 1 room for every 50 block distance") -- unlike the automatic placer's own
 * fixed {@code ROOM_COUNT = 1} (always joining two adjacent houses, so a fixed small count already
 * makes sense there).
 *
 * <p><b>Every tunnel gets its own independent recipe</b> -- {@code ownerCoreId + "_" +
 * tunnelId} (explicit follow-up user spec, same day: "the recipe should be unique for the set of
 * entrances. First stake placed gets a settlement UUID + tunnel UUID, that's the name of the
 * recipe. That way two tunnels of the same length in the same settlement can be different"). {@code
 * tunnelId} is assigned once, at stake-placement time, not here -- see {@link
 * TunnelStakeEntity#create}/{@link TunnelStakeEntity#pair}'s own doc.
 */
public final class TunnelFinalizer {

    private static final double BLOCKS_PER_ROOM = 50.0;

    private TunnelFinalizer() {
    }

    /** Callers must already have confirmed both stakes are paired to each other. */
    public static void finalizePair(TunnelStakeEntity a, TunnelStakeEntity b) {
        if (!(a.level() instanceof ServerLevel levelA) || !(b.level() instanceof ServerLevel levelB)) {
            return;
        }
        BlockPos posA = BlockPos.containing(a.getX(), a.getY(), a.getZ());
        BlockPos posB = BlockPos.containing(b.getX(), b.getY(), b.getZ());
        Direction facingA = directionTowards(posA, posB);
        Direction facingB = facingA.getOpposite();

        int roomCount = roomCountForDistance(posA, posB);
        String recipeName = a.getOwnerCoreId() + "_" + a.getTunnelId();
        TinyWyrldsTunnelBridge.ensureNoLootTunnelRecipeExists(recipeName, roomCount);
        TinyWyrldsTunnelBridge.placePublicTunnelDoorPair(levelA, posA, facingA, levelB, posB, facingB, recipeName);

        TunnelConnectionMarkerEntity.removeTrail(levelA, a.getTunnelId());
        a.discardAfterFinalize();
        b.discardAfterFinalize();
    }

    /** Horizontal (X/Z) distance only -- the real-world Y difference between two settlement doors isn't meaningful to how long the generated passage between them should feel. */
    private static int roomCountForDistance(BlockPos a, BlockPos b) {
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        return Math.max(0, (int) Math.round(distance / BLOCKS_PER_ROOM));
    }

    private static Direction directionTowards(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
