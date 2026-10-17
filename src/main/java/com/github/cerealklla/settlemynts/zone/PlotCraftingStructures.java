package com.github.cerealklla.settlemynts.zone;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.github.cerealklla.cartographyr.geo.Geometry;
import com.github.cerealklla.settlemynts.api.Settlemynts;
import com.github.cerealklla.settlemynts.bridge.LyfeCraftingBridge;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * NPC plot crafting (2026-10-09) -- finds the highest-Tier Lyfe crafting structure physically present
 * on a plot, so {@code CraftingExecutor}/{@code PlannedInventoryClearing} can tell whether a plot is
 * even capable of crafting a given recipe before checking materials. No generic "find block type X in
 * an area" utility exists elsewhere in the suite -- {@code api.Settlemynts#resolvePlotBoxes} resolves
 * Containers through Cartographyr's Box Identity index, which has no equivalent for arbitrary blocks.
 * Deliberately walks only the chunks the plot's polygon bounding box touches, and within each loaded
 * chunk reads its own block-entity map ({@link LevelChunk#getBlockEntities()}) rather than scanning
 * every individual {@link BlockPos} in the volume -- cheap, since a chunk only ever lists the block
 * entities actually placed in it.
 */
public final class PlotCraftingStructures {

    private PlotCraftingStructures() {
    }

    public static int maxCraftingStructureTier(ServerLevel level, UUID plotId) {
        if (!LyfeCraftingBridge.isLoaded()) {
            return 0;
        }
        Optional<Geometry.Polygon> polygon = Settlemynts.resolvePlotPolygon(level, plotId);
        if (polygon.isEmpty()) {
            return 0;
        }
        return maxCraftingStructureTier(level, polygon.get());
    }

    /** Same as {@link #maxCraftingStructureTier(ServerLevel, UUID)}, for a polygon the caller already has in hand -- avoids its own world scan. */
    public static int maxCraftingStructureTier(ServerLevel level, Geometry.Polygon area) {
        if (!LyfeCraftingBridge.isLoaded()) {
            return 0;
        }
        int[] bestTier = {0};
        forEachBlockEntityInArea(level, area, (pos, be) -> {
            int tier = LyfeCraftingBridge.craftingStructureTier(be);
            if (tier > bestTier[0]) {
                bestTier[0] = tier;
            }
        });
        return bestTier[0];
    }

    /**
     * Whether a plain vanilla Furnace is physically present on the plot -- added 2026-10-16, explicit
     * user request: "recognize the furnace as a valid crafting table" (for {@code
     * zone.SmeltingRecipes}, ore-to-ingot smelting). Deliberately independent of {@link
     * LyfeCraftingBridge#isLoaded()} -- a Furnace is pure vanilla, this check (and so smelting-based
     * auto-crafting) works even on a Lyfe-less server, unlike every other check in this class.
     */
    public static boolean hasFurnace(ServerLevel level, UUID plotId) {
        Optional<Geometry.Polygon> polygon = Settlemynts.resolvePlotPolygon(level, plotId);
        return polygon.isPresent() && hasFurnace(level, polygon.get());
    }

    /** Same as {@link #hasFurnace(ServerLevel, UUID)}, for a polygon the caller already has in hand -- avoids its own world scan. */
    public static boolean hasFurnace(ServerLevel level, Geometry.Polygon area) {
        boolean[] found = {false};
        forEachBlockEntityInArea(level, area, (pos, be) -> {
            if (be instanceof FurnaceBlockEntity) {
                found[0] = true;
            }
        });
        return found[0];
    }

    private static void forEachBlockEntityInArea(ServerLevel level, Geometry.Polygon area,
                                                   java.util.function.BiConsumer<BlockPos, BlockEntity> consumer) {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Geometry.Polygon.Vertex v : area.vertices()) {
            minX = Math.min(minX, v.x());
            minZ = Math.min(minZ, v.z());
            maxX = Math.max(maxX, v.x());
            maxZ = Math.max(maxZ, v.z());
        }

        int minChunkX = minX >> 4, maxChunkX = maxX >> 4;
        int minChunkZ = minZ >> 4, maxChunkZ = maxZ >> 4;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                if (!level.hasChunk(cx, cz)) {
                    continue;
                }
                LevelChunk chunk = level.getChunk(cx, cz);
                for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                    BlockPos pos = entry.getKey();
                    if (!area.contains(pos.getX(), pos.getZ())) {
                        continue;
                    }
                    consumer.accept(pos, entry.getValue());
                }
            }
        }
    }
}
