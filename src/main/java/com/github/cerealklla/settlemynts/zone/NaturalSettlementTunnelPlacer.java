package com.github.cerealklla.settlemynts.zone;

import java.util.ArrayList;
import java.util.List;

import com.github.cerealklla.settlemynts.SettlemyntsMod;
import com.github.cerealklla.settlemynts.bridge.TinyWyrldsTunnelBridge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;

/**
 * Places a {@code tinywyrlds} Tunnel door pair between two of a natural settlement's own just-
 * generated plots, the moment {@link NaturalVillagePlotGenerator#processPending} finishes that
 * settlement -- explicit user example: "when an NPC town is generated we might, at time of
 * generation, place two entrances in two separate houses within the settlement connected as A and
 * B for a public tunnel." Always {@link com.github.cerealklla.tinywyrlds.instance.WyrldLifecycle
 * #PUBLIC}, always a bare no-loot/no-mob recipe -- explicit user spec, point 1 of the Tunnel design
 * conversation: "a town placing stakes might always be no loot, no mobs, public." Gated entirely
 * behind {@link TinyWyrldsTunnelBridge#isLoaded()} -- a server without TinyWyrlds loaded just never
 * gets this shortcut, no error, same isolation convention as every other optional bridge here.
 *
 * <p>Picks its two anchor points by re-scanning this same settlement's own real generated building
 * pieces (the exact {@link NaturalVillagePlotGenerator#isLikelyBuildingFootprint} filter the plot
 * generator itself just used, reused directly rather than re-deriving from {@link PlotRecord},
 * which has no real floor-position field for a natural-village plot to begin with) -- the first
 * two that pass are "house A" and "house B." A piece's own {@code BoundingBox#minY()} is used as
 * the floor -- simpler and more reliable than a surface heightmap scan here specifically, since a
 * heightmap lookup inside a roofed building would find the roof, not the interior floor. A village
 * with fewer than 2 real building pieces (a very small one) simply gets no Tunnel -- not an error,
 * just nothing to connect.
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
            if (buildingBoxes.size() >= 2) {
                break;
            }
        }
        if (buildingBoxes.size() < 2) {
            return;
        }

        TinyWyrldsTunnelBridge.ensureNoLootTunnelRecipeExists(RECIPE_NAME, ROOM_COUNT);

        BoundingBox boxA = buildingBoxes.get(0);
        BoundingBox boxB = buildingBoxes.get(1);
        BlockPos posA = floorCenter(boxA);
        BlockPos posB = floorCenter(boxB);
        Direction facingA = directionTowards(posA, posB);
        Direction facingB = facingA.getOpposite();

        TinyWyrldsTunnelBridge.placePublicTunnelDoorPair(level, posA, facingA, level, posB, facingB, RECIPE_NAME);
        SettlemyntsMod.LOGGER.info("Placed a public Tunnel door pair for natural settlement {} at {} <-> {}",
                entry.settlement(), posA, posB);
    }

    private static BlockPos floorCenter(BoundingBox box) {
        int x = (box.minX() + box.maxX()) / 2;
        int z = (box.minZ() + box.maxZ()) / 2;
        return new BlockPos(x, box.minY() + 1, z);
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
