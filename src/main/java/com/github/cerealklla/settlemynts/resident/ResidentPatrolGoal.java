package com.github.cerealklla.settlemynts.resident;

import java.util.EnumSet;

import com.github.cerealklla.cartographyr.geo.Geometry;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Confines a {@link ResidentVillagerEntity}'s wandering to its own plot's "Town Proper" buffer
 * polygon (see {@link ResidentVillagerEntity#resolvePatrolArea}) -- a near-verbatim copy of {@code
 * guardhouse.GuardPatrolAreaGoal}, which solves the identical problem for garrison guards. Kept as
 * its own class rather than a shared generic goal, matching this codebase's existing precedent of
 * one small goal class per entity type rather than an early abstraction over two conceptually
 * distinct mobs.
 */
public class ResidentPatrolGoal extends Goal {

    private static final int MAX_SAMPLE_ATTEMPTS = 10;
    private static final int REROLL_CHANCE_DENOMINATOR = 100;

    private final ResidentVillagerEntity resident;
    private final double speedModifier;

    public ResidentPatrolGoal(ResidentVillagerEntity resident, double speedModifier) {
        this.resident = resident;
        this.speedModifier = speedModifier;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!resident.getNavigation().isDone()) {
            return false;
        }
        if (resident.getRandom().nextInt(REROLL_CHANCE_DENOMINATOR) != 0) {
            return false;
        }
        return resident.level() instanceof ServerLevel level && resident.resolvePatrolArea(level).isPresent();
    }

    @Override
    public boolean canContinueToUse() {
        return false; // One-shot per activation -- start() issues the move, canUse() re-rolls later.
    }

    @Override
    public void start() {
        if (!(resident.level() instanceof ServerLevel level)) {
            return;
        }
        resident.resolvePatrolArea(level).ifPresent(area -> {
            BlockPos target = pickTarget(level, area);
            if (target != null) {
                resident.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, speedModifier);
            }
        });
    }

    private BlockPos pickTarget(ServerLevel level, Geometry.Polygon area) {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Geometry.Polygon.Vertex v : area.vertices()) {
            minX = Math.min(minX, v.x());
            minZ = Math.min(minZ, v.z());
            maxX = Math.max(maxX, v.x());
            maxZ = Math.max(maxZ, v.z());
        }
        int currentX = Mth.floor(resident.getX());
        int currentZ = Mth.floor(resident.getZ());

        if (!area.contains(currentX, currentZ)) {
            int centerX = (minX + maxX) / 2;
            int centerZ = (minZ + maxZ) / 2;
            for (int attempt = 0; attempt < MAX_SAMPLE_ATTEMPTS; attempt++) {
                int x = centerX + resident.getRandom().nextInt(5) - 2;
                int z = centerZ + resident.getRandom().nextInt(5) - 2;
                if (area.contains(x, z)) {
                    return surfacePos(level, x, z);
                }
            }
            return surfacePos(level, centerX, centerZ);
        }

        int rangeX = Math.max(1, maxX - minX + 1);
        int rangeZ = Math.max(1, maxZ - minZ + 1);
        for (int attempt = 0; attempt < MAX_SAMPLE_ATTEMPTS; attempt++) {
            int x = minX + resident.getRandom().nextInt(rangeX);
            int z = minZ + resident.getRandom().nextInt(rangeZ);
            if (area.contains(x, z)) {
                return surfacePos(level, x, z);
            }
        }
        return null;
    }

    private static BlockPos surfacePos(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new BlockPos(x, y, z);
    }
}
