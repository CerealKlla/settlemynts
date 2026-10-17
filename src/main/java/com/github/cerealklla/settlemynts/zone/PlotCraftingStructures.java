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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

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
     * Same as {@link #maxCraftingStructureTier}, but for a Lyfe Cooking Station/Grill/Stove/Oven/Chef
     * Set ({@code cook.CookingStructureBlockEntity}) instead of a crafting structure -- added
     * 2026-10-17, real report: a Restaurant plot's Cooking Station was invisible to the auto-crafting
     * engine entirely, since it's a structurally distinct block entity from {@code
     * craft.CraftingStructureBlockEntity} (mirrors it, but isn't a subtype of it).
     */
    public static int maxCookingStructureTier(ServerLevel level, UUID plotId) {
        if (!LyfeCraftingBridge.isLoaded()) {
            return 0;
        }
        Optional<Geometry.Polygon> polygon = Settlemynts.resolvePlotPolygon(level, plotId);
        if (polygon.isEmpty()) {
            return 0;
        }
        return maxCookingStructureTier(level, polygon.get());
    }

    /** Same as {@link #maxCookingStructureTier(ServerLevel, UUID)}, for a polygon the caller already has in hand -- avoids its own world scan. */
    public static int maxCookingStructureTier(ServerLevel level, Geometry.Polygon area) {
        if (!LyfeCraftingBridge.isLoaded()) {
            return 0;
        }
        int[] bestTier = {0};
        forEachBlockEntityInArea(level, area, (pos, be) -> {
            int tier = LyfeCraftingBridge.cookingStructureTier(be);
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

    /**
     * Whether a plain vanilla Stonecutter is physically present on the plot -- added 2026-10-16,
     * explicit user request: "can we do the same fix for that which we did for the furnace" (for
     * {@code zone.StonecuttingRecipes}). Deliberately independent of {@link
     * LyfeCraftingBridge#isLoaded()}, same reasoning as {@link #hasFurnace}.
     *
     * <p><b>Can't reuse {@link #forEachBlockEntityInArea}</b> -- unlike a Furnace, a Stonecutter has
     * no {@link BlockEntity} at all (confirmed against the decompiled source: it's a stateless block,
     * nothing to track burn progress or similar), so it's invisible to a chunk's own block-entity map.
     * This instead does a real bounded {@link net.minecraft.world.level.block.state.BlockState} scan:
     * for each column in the polygon (capped by {@link #STONECUTTER_SCAN_MAX_COLUMNS}, same bounded-
     * scan shape {@code farm.FarmerWorkGoal}/{@code lumberyard.LumberjackWorkGoal} already use for
     * their own per-column work-target scans), checks a vertical band anchored to that column's own
     * heightmap surface -- wide enough to catch a Stonecutter sitting anywhere in a realistic 1-2
     * story workshop at that spot, not just a column's exact topmost block (which would miss one
     * placed indoors under a roof).
     */
    private static final int STONECUTTER_SCAN_MAX_COLUMNS = 400;
    private static final int STONECUTTER_SCAN_BELOW = 6;
    private static final int STONECUTTER_SCAN_ABOVE = 12;

    public static boolean hasStonecutter(ServerLevel level, UUID plotId) {
        Optional<Geometry.Polygon> polygon = Settlemynts.resolvePlotPolygon(level, plotId);
        return polygon.isPresent() && hasStonecutter(level, polygon.get());
    }

    /** Same as {@link #hasStonecutter(ServerLevel, UUID)}, for a polygon the caller already has in hand -- avoids its own world scan. */
    public static boolean hasStonecutter(ServerLevel level, Geometry.Polygon area) {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Geometry.Polygon.Vertex v : area.vertices()) {
            minX = Math.min(minX, v.x());
            minZ = Math.min(minZ, v.z());
            maxX = Math.max(maxX, v.x());
            maxZ = Math.max(maxZ, v.z());
        }
        int scanned = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!area.contains(x, z)) {
                    continue;
                }
                if (scanned++ > STONECUTTER_SCAN_MAX_COLUMNS) {
                    return false;
                }
                int topY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                for (int y = topY - STONECUTTER_SCAN_BELOW; y <= topY + STONECUTTER_SCAN_ABOVE; y++) {
                    if (level.getBlockState(new BlockPos(x, y, z)).is(Blocks.STONECUTTER)) {
                        return true;
                    }
                }
            }
        }
        return false;
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
