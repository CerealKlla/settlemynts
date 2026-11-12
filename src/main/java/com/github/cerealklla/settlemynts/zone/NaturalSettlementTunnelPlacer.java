package com.github.cerealklla.settlemynts.zone;

import java.util.ArrayList;
import java.util.List;

import com.github.cerealklla.settlemynts.SettlemyntsMod;
import com.github.cerealklla.settlemynts.bridge.TinyWyrldsTunnelBridge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;

/**
 * Places a {@code tinywyrlds} Tunnel door group between several of a natural settlement's own
 * just-generated plots, the moment {@link NaturalVillagePlotGenerator#processPending} finishes
 * that settlement -- explicit user example: "when an NPC town is generated we might, at time of
 * generation, place two entrances in two separate houses within the settlement connected as A and
 * B for a public tunnel." Always {@link com.github.cerealklla.tinywyrlds.instance.WyrldLifecycle
 * #PUBLIC}, always a bare no-loot/no-mob recipe -- explicit user spec, point 1 of the Tunnel design
 * conversation: "a town placing stakes might always be no loot, no mobs, public." Gated entirely
 * behind {@link TinyWyrldsTunnelBridge#isLoaded()} -- a server without TinyWyrlds loaded just never
 * gets this shortcut, no error, same isolation convention as every other optional bridge here.
 *
 * <p>Picks its anchor points by re-scanning this same settlement's own real generated building
 * pieces (the exact {@link NaturalVillagePlotGenerator#isLikelyBuildingFootprint} filter the plot
 * generator itself just used, reused directly rather than re-deriving from {@link PlotRecord},
 * which has no real floor-position field for a natural-village plot to begin with). A piece's own
 * {@code BoundingBox#minY()} is used as the floor -- simpler and more reliable than a surface
 * heightmap scan here specifically, since a heightmap lookup inside a roofed building would find
 * the roof, not the interior floor. A village with fewer than 2 real building pieces (a very small
 * one) simply gets no Tunnel -- not an error, just nothing to connect.
 *
 * <p><b>Door count is randomized based on how many qualifying buildings the village actually
 * has</b> -- explicit user correction, 2026-11-12, after an initial fixed cap of 4 was flagged as
 * an arbitrary, unrequested number: "it should be randomized based on the number of buildings
 * present, not an arbitrary limit." Every qualifying building becomes a candidate (no early-exit
 * cap at all); a uniformly random count between 2 and that candidate pool's own size (inclusive)
 * is drawn, then that many candidates are picked via a Fisher-Yates shuffle (so a small village's
 * own real building count is the only ceiling, and a large village doesn't deterministically
 * always wire in the same handful of buildings every time).
 *
 * <p>Each door's own facing points toward the group's own centroid (every door included) -- for
 * exactly 2 doors this is identical to the original "face the other door" rule (the centroid of 2
 * points sits on the line between them, so the bearing toward it is the same bearing as toward the
 * other point directly), and generalizes sensibly for more by having every door face roughly
 * toward the middle of the group.
 *
 * <p>Room count/recipe name are fixed constants for now (one shared recipe, reused by every
 * natural settlement) -- a future per-settlement-size or per-biome variation is real, flagged
 * follow-up work, not built here.
 */
public final class NaturalSettlementTunnelPlacer {

    private static final String RECIPE_NAME = "NaturalSettlementShortcut";
    private static final int ROOM_COUNT = 1;

    private NaturalSettlementTunnelPlacer() {
    }

    public static void maybePlaceTunnel(ServerLevel level, NaturalVillagePlotPending.Entry entry) {
        if (!TinyWyrldsTunnelBridge.isLoaded()) {
            return;
        }
        List<BoundingBox> buildingBoxes = new ArrayList<>();
        for (StructurePiece piece : entry.start().getPieces()) {
            BoundingBox box = piece.getBoundingBox();
            if (NaturalVillagePlotGenerator.isLikelyBuildingFootprint(piece, box)) {
                buildingBoxes.add(box);
            }
        }
        if (buildingBoxes.size() < 2) {
            return;
        }

        RandomSource random = level.getRandom();
        for (int i = buildingBoxes.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            BoundingBox swap = buildingBoxes.get(i);
            buildingBoxes.set(i, buildingBoxes.get(j));
            buildingBoxes.set(j, swap);
        }
        int doorCount = buildingBoxes.size() == 2 ? 2 : 2 + random.nextInt(buildingBoxes.size() - 1);
        buildingBoxes = buildingBoxes.subList(0, doorCount);

        TinyWyrldsTunnelBridge.ensureNoLootTunnelRecipeExists(RECIPE_NAME, ROOM_COUNT);

        List<BlockPos> positions = buildingBoxes.stream().map(NaturalSettlementTunnelPlacer::floorCenter).toList();
        double centerX = positions.stream().mapToInt(BlockPos::getX).average().orElse(0);
        double centerZ = positions.stream().mapToInt(BlockPos::getZ).average().orElse(0);

        List<TinyWyrldsTunnelBridge.DoorPlacement> doors = new ArrayList<>();
        for (BlockPos pos : positions) {
            Direction facing = directionTowards(pos, centerX, centerZ);
            doors.add(new TinyWyrldsTunnelBridge.DoorPlacement(level, pos, facing));
        }

        TinyWyrldsTunnelBridge.placePublicTunnelDoorGroup(doors, RECIPE_NAME);
        SettlemyntsMod.LOGGER.info("Placed a public Tunnel door group ({} door(s)) for natural settlement {} at {}",
                positions.size(), entry.settlement(), positions);
    }

    private static BlockPos floorCenter(BoundingBox box) {
        int x = (box.minX() + box.maxX()) / 2;
        int z = (box.minZ() + box.maxZ()) / 2;
        return new BlockPos(x, box.minY() + 1, z);
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
