package com.github.cerealklla.settlemynts.farm;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.github.cerealklla.cartographyr.geo.Geometry;
import com.github.cerealklla.settlemynts.api.Settlemynts;
import com.github.cerealklla.settlemynts.farm.FarmCropSpecies.CropChoice;
import com.github.cerealklla.settlemynts.farm.FarmCropSpecies.PlantKind;
import com.github.cerealklla.settlemynts.zone.ContainerDeposit;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * A Farm worker's actual job (added 2026-10-05, explicit user request: till/plant in rows, harvest
 * and deposit into the plot's boxes) -- same self-contained state-machine shape as {@code
 * lumberyard.LumberjackWorkGoal}, see that class's own doc for the shared conventions (throttled
 * {@code canUse}, arrive-then-act via {@code tick}, fully live-world-state-derived, no persisted
 * memory).
 *
 * <p>Row planting: only every other column along the polygon's local X axis is a plantable row
 * ({@code (x - minX) % ROW_SPACING == 0}), leaving a walking gap between rows -- a simple, readable
 * way to satisfy "plants in rows" without a full row/furrow rendering system.
 *
 * <p>See {@code lumberyard.LumberjackWorkGoal}'s own doc for the live re-validation convention
 * mirrored here identically in {@link #plant} (never overwrite anything that isn't genuinely
 * clearable, and re-check the ground is still real soil right before tilling it). The "one lap per
 * day" gate both goals used to share is gone (2026-10-06, explicit user request: back to constantly
 * working -- it was observed getting workers stuck/stopped trying).
 *
 * <p><b>Give-up timeout added, 2026-10-06</b> -- same fix, same bug, same reasoning as {@code
 * LumberjackWorkGoal}'s identical addition (see that class's own doc for the full root-cause
 * writeup): no timeout meant a target the Farmer couldn't actually path to left it permanently stuck
 * re-issuing the same {@code moveTo} forever, since {@code canContinueToUse} never went false.
 */
public class FarmerWorkGoal extends Goal {

    private static final int REROLL_CHANCE_DENOMINATOR = 20;
    private static final double ARRIVE_DIST_SQ = 4.0;
    private static final int ROW_SPACING = 2;
    private static final int MAX_COLUMNS_SCANNED = 400;
    private static final Set<Block> TILLABLE_SOIL = Set.of(
            Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT);

    private static final int MAX_PURSUE_TICKS = 100; // ~5s of real failure to arrive -- give up.
    private static final long AVOID_DURATION_TICKS = 1200; // 1 minute before retrying the same spot.
    private static final double AVOID_RADIUS_BLOCKS = 3.0;

    private final FarmerWorkerEntity worker;
    private final double speedModifier;

    private BlockPos pendingTarget;
    private boolean pendingIsHarvest;
    private int repathCooldown;
    private int pursueTicks;
    private final List<AvoidedTarget> recentlyAvoided = new ArrayList<>();

    private record AvoidedTarget(BlockPos pos, long expiresAtGameTime) {
    }

    public FarmerWorkGoal(FarmerWorkerEntity worker, double speedModifier) {
        this.worker = worker;
        this.speedModifier = speedModifier;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!worker.getNavigation().isDone()) {
            return false;
        }
        if (worker.getRandom().nextInt(REROLL_CHANCE_DENOMINATOR) != 0) {
            return false;
        }
        if (!(worker.level() instanceof ServerLevel level)) {
            return false;
        }
        Optional<Geometry.Polygon> area = worker.resolvePatrolArea(level);
        if (area.isEmpty()) {
            return false;
        }
        WorkTarget found = findTarget(level, area.get());
        if (found == null) {
            return false;
        }
        pendingTarget = found.pos();
        pendingIsHarvest = found.isHarvest();
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return pendingTarget != null;
    }

    @Override
    public void start() {
        repathCooldown = 0;
        pursueTicks = 0;
        if (pendingTarget != null) {
            worker.getNavigation().moveTo(pendingTarget.getX() + 0.5, pendingTarget.getY(), pendingTarget.getZ() + 0.5, speedModifier);
        }
    }

    @Override
    public void stop() {
        pendingTarget = null;
    }

    @Override
    public void tick() {
        if (pendingTarget == null || !(worker.level() instanceof ServerLevel level)) {
            return;
        }
        if (worker.blockPosition().distSqr(pendingTarget) > ARRIVE_DIST_SQ) {
            if (++pursueTicks > MAX_PURSUE_TICKS) {
                recentlyAvoided.add(new AvoidedTarget(pendingTarget, level.getGameTime() + AVOID_DURATION_TICKS));
                pendingTarget = null;
                return;
            }
            if (worker.getNavigation().isDone() && repathCooldown-- <= 0) {
                worker.getNavigation().moveTo(pendingTarget.getX() + 0.5, pendingTarget.getY(), pendingTarget.getZ() + 0.5, speedModifier);
                repathCooldown = 20;
            }
            return;
        }
        Optional<Geometry.Polygon> area = worker.resolvePatrolArea(level);
        CropChoice crop = FarmCropSpecies.chooseFor(level, area.map(this::boundingBoxCenter).orElse(pendingTarget));
        if (pendingIsHarvest) {
            harvest(level, pendingTarget, crop);
        } else {
            plant(level, pendingTarget, crop);
        }
        pendingTarget = null;
    }

    private record WorkTarget(BlockPos pos, boolean isHarvest) {
    }

    /** Prunes expired entries, then true if {@code pos} is within {@link #AVOID_RADIUS_BLOCKS} of a still-active one. */
    private boolean isAvoided(ServerLevel level, BlockPos pos) {
        long now = level.getGameTime();
        Iterator<AvoidedTarget> it = recentlyAvoided.iterator();
        boolean avoided = false;
        while (it.hasNext()) {
            AvoidedTarget avoidedTarget = it.next();
            if (avoidedTarget.expiresAtGameTime() <= now) {
                it.remove();
                continue;
            }
            if (avoidedTarget.pos().distSqr(pos) <= AVOID_RADIUS_BLOCKS * AVOID_RADIUS_BLOCKS) {
                avoided = true;
            }
        }
        return avoided;
    }

    private WorkTarget findTarget(ServerLevel level, Geometry.Polygon area) {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Geometry.Polygon.Vertex v : area.vertices()) {
            minX = Math.min(minX, v.x());
            minZ = Math.min(minZ, v.z());
            maxX = Math.max(maxX, v.x());
            maxZ = Math.max(maxZ, v.z());
        }
        CropChoice crop = FarmCropSpecies.chooseFor(level, boundingBoxCenter(minX, minZ, maxX, maxZ));

        BlockPos firstPlantCandidate = null;
        int scanned = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!area.contains(x, z)) {
                    continue;
                }
                if (scanned++ > MAX_COLUMNS_SCANNED) {
                    return firstPlantCandidate == null ? null : new WorkTarget(firstPlantCandidate, false);
                }
                int topY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos topSolid = new BlockPos(x, topY - 1, z);
                BlockState topState = level.getBlockState(topSolid);

                if (crop.kind() == PlantKind.STEM_FRUIT && topState.is(crop.fruitBlock()) && !isAvoided(level, topSolid)) {
                    return new WorkTarget(topSolid, true); // A ripe melon fruit -- harvest wins immediately.
                }
                if (crop.kind() == PlantKind.SIMPLE_CROP && topState.is(Blocks.FARMLAND)) {
                    BlockPos cropPos = topSolid.above();
                    BlockState cropState = level.getBlockState(cropPos);
                    if (cropState.is(crop.plantBlock()) && cropState.getBlock() instanceof CropBlock cropBlock
                            && cropBlock.isMaxAge(cropState) && !isAvoided(level, cropPos)) {
                        return new WorkTarget(cropPos, true);
                    }
                }

                if (firstPlantCandidate == null && (x - minX) % ROW_SPACING == 0
                        && TILLABLE_SOIL.contains(topState.getBlock())
                        && level.getBlockState(topSolid.above()).canBeReplaced()
                        && !isAvoided(level, topSolid)) {
                    firstPlantCandidate = topSolid;
                }
            }
        }
        return firstPlantCandidate == null ? null : new WorkTarget(firstPlantCandidate, false);
    }

    private BlockPos boundingBoxCenter(Geometry.Polygon area) {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Geometry.Polygon.Vertex v : area.vertices()) {
            minX = Math.min(minX, v.x());
            minZ = Math.min(minZ, v.z());
            maxX = Math.max(maxX, v.x());
            maxZ = Math.max(maxZ, v.z());
        }
        return boundingBoxCenter(minX, minZ, maxX, maxZ);
    }

    private BlockPos boundingBoxCenter(int minX, int minZ, int maxX, int maxZ) {
        return new BlockPos((minX + maxX) / 2, 0, (minZ + maxZ) / 2);
    }

    private void plant(ServerLevel level, BlockPos soilPos, CropChoice crop) {
        // Live re-check (2026-10-05, real report: NPCs destroying non-soil/solid blocks while
        // planting) -- only till ground that's still genuinely tillable soil (or already farmland);
        // never touch anything else. Grass/flowers/snow layers above are fine to clear (per explicit
        // user instruction) via canBeReplaced(), but a real solid block never is.
        BlockState groundState = level.getBlockState(soilPos);
        boolean alreadyFarmland = groundState.is(Blocks.FARMLAND);
        if (!alreadyFarmland && !TILLABLE_SOIL.contains(groundState.getBlock())) {
            return;
        }
        if (!alreadyFarmland) {
            level.setBlock(soilPos, Blocks.FARMLAND.defaultBlockState(), 3);
        }
        BlockPos above = soilPos.above();
        if (level.getBlockState(above).canBeReplaced()) {
            level.setBlock(above, crop.plantBlock().defaultBlockState(), 3);
        }
    }

    private void harvest(ServerLevel level, BlockPos pos, CropChoice crop) {
        BlockState state = level.getBlockState(pos);
        boolean stillValid = crop.kind() == PlantKind.STEM_FRUIT
                ? state.is(crop.fruitBlock())
                : state.is(crop.plantBlock()) && state.getBlock() instanceof CropBlock cropBlock && cropBlock.isMaxAge(state);
        if (!stillValid) {
            return; // Drifted since the scan (e.g. a player already harvested it) -- leave it alone.
        }
        level.removeBlock(pos, false);
        List<Container> boxes = Settlemynts.resolvePlotBoxes(level, worker.plotId());
        if (!boxes.isEmpty()) {
            ContainerDeposit.depositIntoAny(boxes, new ItemStack(crop.produceItem(), crop.produceCount()));
        }
        if (crop.kind() == PlantKind.SIMPLE_CROP) {
            level.setBlock(pos, crop.plantBlock().defaultBlockState(), 3); // Replant at age 0 immediately.
        }
        // STEM_FRUIT: only the fruit was broken -- the stem stays and regrows another fruit on its own.
    }
}
