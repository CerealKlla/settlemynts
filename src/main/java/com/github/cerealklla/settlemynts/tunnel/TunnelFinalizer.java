package com.github.cerealklla.settlemynts.tunnel;

import java.util.ArrayList;
import java.util.List;

import com.github.cerealklla.settlemynts.bridge.TinyWyrldsTunnelBridge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/**
 * Converts a joined {@link TunnelStakeEntity} group into a real Tunnel door group -- the Mayor/
 * Town-Planner-placed counterpart of {@code zone.NaturalSettlementTunnelPlacer}'s automatic one.
 * Always {@code WyrldLifecycle#PUBLIC} + a bare no-loot/no-mob recipe, same reasoning as that class
 * ("a town placing stakes might always be no loot, no mobs, public" -- explicit user spec). Widened
 * 2026-11-12 from a strict pair to an arbitrary-size group (2+ stakes), per explicit user spec:
 * "certain settlements could have multiple entrances to the same underground sewer system."
 *
 * <p><b>Room count is dynamic, based on the real-world distance between stakes</b> (explicit user
 * spec, 2026-11-11: "dynamically determine the number of rooms based on distances between the
 * entrance stakes, maybe 1 room for every 50 block distance") -- unlike the automatic placer's own
 * fixed {@code ROOM_COUNT = 1} (always joining adjacent houses, so a fixed small count already
 * makes sense there). Generalized to N stakes via the single farthest-apart pair in the group (the
 * group's own diameter) -- identical to the original 2-stake formula when there are only 2, and a
 * reasonable "how long does this shortcut feel" proxy for more.
 *
 * <p><b>Every tunnel gets its own independent recipe</b> -- {@code ownerCoreId + "_" +
 * tunnelId} (explicit follow-up user spec, same day: "the recipe should be unique for the set of
 * entrances. First stake placed gets a settlement UUID + tunnel UUID, that's the name of the
 * recipe. That way two tunnels of the same length in the same settlement can be different"). {@code
 * tunnelId} is assigned once, at stake-placement time, not here -- see {@link
 * TunnelStakeEntity#create}/{@link TunnelStakeEntity#join}'s own doc.
 */
public final class TunnelFinalizer {

    private static final double BLOCKS_PER_ROOM = 50.0;

    private TunnelFinalizer() {
    }

    /** Callers must already have confirmed every stake shares the same {@link TunnelStakeEntity#getTunnelId()} and that {@code stakes.size() >= 2}. */
    public static void finalizeGroup(List<TunnelStakeEntity> stakes) {
        List<BlockPos> positions = new ArrayList<>();
        List<ServerLevel> levels = new ArrayList<>();
        for (TunnelStakeEntity stake : stakes) {
            if (!(stake.level() instanceof ServerLevel stakeLevel)) {
                return;
            }
            levels.add(stakeLevel);
            positions.add(BlockPos.containing(stake.getX(), stake.getY(), stake.getZ()));
        }

        double centerX = positions.stream().mapToInt(BlockPos::getX).average().orElse(0);
        double centerZ = positions.stream().mapToInt(BlockPos::getZ).average().orElse(0);

        List<TinyWyrldsTunnelBridge.DoorPlacement> doors = new ArrayList<>();
        for (int i = 0; i < stakes.size(); i++) {
            Direction facing = directionTowards(positions.get(i), centerX, centerZ);
            doors.add(new TinyWyrldsTunnelBridge.DoorPlacement(levels.get(i), positions.get(i), facing));
        }

        int roomCount = roomCountForDiameter(positions);
        String recipeName = stakes.get(0).getOwnerCoreId() + "_" + stakes.get(0).getTunnelId();
        TinyWyrldsTunnelBridge.ensureNoLootTunnelRecipeExists(recipeName, roomCount);
        TinyWyrldsTunnelBridge.placePublicTunnelDoorGroup(doors, recipeName);

        for (int i = 0; i < stakes.size(); i++) {
            TunnelStakeEntity stake = stakes.get(i);
            for (java.util.UUID otherId : stake.getConnectionIds()) {
                TunnelConnectionMarkerEntity.removeTrail(levels.get(i), positions.get(i), stake.getTunnelId(), stake.getUUID(), otherId);
            }
        }
        for (TunnelStakeEntity stake : stakes) {
            stake.discardAfterFinalize();
        }
    }

    /** Horizontal (X/Z) distance between the single farthest-apart pair of stakes in the group --
     *  the real-world Y difference between settlement doors isn't meaningful to how long the
     *  generated passage should feel. */
    private static int roomCountForDiameter(List<BlockPos> positions) {
        double maxDistance = 0;
        for (int i = 0; i < positions.size(); i++) {
            for (int j = i + 1; j < positions.size(); j++) {
                double dx = positions.get(j).getX() - positions.get(i).getX();
                double dz = positions.get(j).getZ() - positions.get(i).getZ();
                maxDistance = Math.max(maxDistance, Math.sqrt(dx * dx + dz * dz));
            }
        }
        return Math.max(0, (int) Math.round(maxDistance / BLOCKS_PER_ROOM));
    }

    private static Direction directionTowards(BlockPos pos, double centerX, double centerZ) {
        double dx = centerX - pos.getX();
        double dz = centerZ - pos.getZ();
        if (dx == 0 && dz == 0) {
            return Direction.NORTH;
        }
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
