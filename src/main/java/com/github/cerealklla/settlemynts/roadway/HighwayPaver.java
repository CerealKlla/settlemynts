package com.github.cerealklla.settlemynts.roadway;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import com.github.cerealklla.cartographyr.api.Cartography;
import com.github.cerealklla.cartographyr.geo.Classification;
import com.github.cerealklla.cartographyr.geo.EntityDefinition;
import com.github.cerealklla.cartographyr.geo.EntityType;
import com.github.cerealklla.cartographyr.geo.Geometry;
import com.github.cerealklla.cartographyr.geo.Layer;
import com.github.cerealklla.cartographyr.geo.LifecycleState;
import com.github.cerealklla.settlemynts.SettlemyntsMod;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.registration.ModBlocks;
import com.github.cerealklla.settlemynts.zone.PlotGeometry;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * A copy of {@link RoadwayPaver}, tweaked for inter-settlement Highway Stakes (added 2026-10-11):
 * {@code core} is {@code @Nullable} throughout, since a road endpoint may sit near a natural
 * (NPC-owned) settlement with no real {@link GhostTownHallCoreEntity} at all. A {@code null} core
 * means "no plot-crossing check, no settlement-specific Tier look" for that edge -- a reasonable
 * degradation, not a crash: a natural village has no finalized plots in this mod's own data model
 * to protect anyway, and defaults to Tier 1's plain look.
 *
 * <p>True reuse of {@link RoadwayPaver} itself wasn't possible without either subclassing {@link
 * RoadwayStakeEntity} (ruled out -- {@link HighwayStakeEntity} is deliberately a separate copy, not
 * a subclass) or widening its signature to accept a nullable core for every caller, including the
 * original intra-settlement feature where a core is always real. A parallel copy keeps that
 * original feature's behavior completely untouched.
 *
 * <p><b>Tier is passed in explicitly, not resolved from {@code core}</b> (added 2026-10-11, explicit
 * user spec: "the actual quality of the road will be determined by the average Tier of the two
 * endpoint settlements" -- a brand-new concept, see {@link SettlementTierResolver}). Unlike
 * {@link RoadwayPaver} (where a single settlement's own current Tier applies uniformly), a Highway
 * has two different endpoint settlements and should pave at one single, fixed quality for its whole
 * length -- computed once at quest assignment (see {@code bridge.MyssionsRoadQuestBridge}), not
 * re-derived per edge from whichever settlement happens to be nearest.
 */
public final class HighwayPaver {

    private static final int ROAD_SIDE_OFFSET_BLOCKS = 1;
    private static final int CLEARANCE_BLOCKS = 4;
    private static final double PATH_HALF_WIDTH_BLOCKS = 1.5;
    private static final int MAX_SLOPE_PER_STEP = 1;

    private HighwayPaver() {
    }

    public record SnapshotEntry(BlockPos pos, BlockState state) {
        public static final Codec<SnapshotEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockPos.CODEC.fieldOf("pos").forGetter(SnapshotEntry::pos),
                BlockState.CODEC.fieldOf("state").forGetter(SnapshotEntry::state)
        ).apply(i, SnapshotEntry::new));
    }

    public static void restoreEdge(ServerLevel level, List<SnapshotEntry> snapshot) {
        for (SnapshotEntry entry : snapshot) {
            level.setBlock(entry.pos(), entry.state(), 3);
        }
    }

    private static int naturalSurfaceY(ServerLevel level, int x, int z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
    }

    private static int[] medianSmooth(int[] values) {
        int n = values.length;
        int[] result = values.clone();
        for (int i = 1; i < n - 1; i++) {
            result[i] = median3(values[i - 1], values[i], values[i + 1]);
        }
        return result;
    }

    private static int median3(int a, int b, int c) {
        return Math.max(Math.min(a, b), Math.min(Math.max(a, b), c));
    }

    public static boolean exceedsMaxSlope(int x1, int z1, int y1, int x2, int z2, int y2) {
        int horizontalSteps = Geometry.Polygon.supercoverLine(
                new Geometry.Polygon.Vertex(x1, z1), new Geometry.Polygon.Vertex(x2, z2)).size() - 1;
        if (horizontalSteps <= 0) {
            return y1 != y2;
        }
        return Math.abs(y2 - y1) > horizontalSteps * MAX_SLOPE_PER_STEP;
    }

    /** No-op (never crosses) when {@code core} is {@code null} -- a natural settlement has no finalized plots in this data model to protect. */
    public static boolean crossesPlot(ServerLevel level, GhostTownHallCoreEntity core, int x1, int z1, int x2, int z2) {
        if (core == null) {
            return false;
        }
        for (Geometry.Polygon.Vertex cell : Geometry.Polygon.supercoverLine(
                new Geometry.Polygon.Vertex(x1, z1), new Geometry.Polygon.Vertex(x2, z2))) {
            if (PlotGeometry.findContainingPlot(level, core, cell.x(), cell.z()).isPresent()) {
                return true;
            }
        }
        return false;
    }

    public static void paveEdge(ServerLevel level, GhostTownHallCoreEntity core, int tier, HighwayStakeEntity a, HighwayStakeEntity b) {
        int x1 = Mth.floor(a.getX());
        int z1 = Mth.floor(a.getZ());
        int x2 = Mth.floor(b.getX());
        int z2 = Mth.floor(b.getZ());
        if (crossesPlot(level, core, x1, z1, x2, z2)) {
            return;
        }

        List<Geometry.Polygon.Vertex> centerline = Geometry.Polygon.supercoverLine(
                new Geometry.Polygon.Vertex(x1, z1), new Geometry.Polygon.Vertex(x2, z2));
        int n = centerline.size();
        int[] naturalY = new int[n];
        for (int i = 0; i < n; i++) {
            Geometry.Polygon.Vertex v = centerline.get(i);
            naturalY[i] = naturalSurfaceY(level, v.x(), v.z());
        }
        int[] profile = slopeLimitedProfile(medianSmooth(naturalY), Mth.floor(a.getY()) - 1, Mth.floor(b.getY()) - 1);

        List<SnapshotEntry> snapshot = new ArrayList<>();
        Set<BlockPos> captured = new HashSet<>();
        for (int i = 0; i < n; i++) {
            Geometry.Polygon.Vertex v = centerline.get(i);
            int dx;
            int dz;
            if (i + 1 < n) {
                dx = centerline.get(i + 1).x() - v.x();
                dz = centerline.get(i + 1).z() - v.z();
            } else {
                dx = v.x() - centerline.get(i - 1).x();
                dz = v.z() - centerline.get(i - 1).z();
            }
            int perpX = -Integer.signum(dz);
            int perpZ = Integer.signum(dx);

            for (int offset = -ROAD_SIDE_OFFSET_BLOCKS; offset <= ROAD_SIDE_OFFSET_BLOCKS; offset++) {
                int colX = v.x() + perpX * offset;
                int colZ = v.z() + perpZ * offset;
                if (offset != 0 && core != null && PlotGeometry.findContainingPlot(level, core, colX, colZ).isPresent()) {
                    continue;
                }
                int natural = offset == 0 ? naturalY[i] : naturalSurfaceY(level, colX, colZ);
                int targetY = profile[i];
                paveColumn(level, colX, targetY, colZ, natural, tier, snapshot, captured);
            }
        }

        var roadEntity = Cartography.createEntity(level, new EntityDefinition(
                level.dimension(), Classification.CONSTRUCTED, EntityType.ROAD, Layer.ROADWAY_ID,
                Optional.empty(), new Geometry.Path(centerline, PATH_HALF_WIDTH_BLOCKS), LifecycleState.REALIZED, Optional.empty()));
        a.setSnapshotFor(b.getUUID(), snapshot, roadEntity.id());
        b.setSnapshotFor(a.getUUID(), snapshot, roadEntity.id());

        SettlemyntsMod.LOGGER.info("[Highway] Added road: {} centerline cells, {} snapshot entries captured", n, snapshot.size());
    }

    private static void captureOriginal(ServerLevel level, BlockPos pos, List<SnapshotEntry> snapshot, Set<BlockPos> captured) {
        if (captured.add(pos)) {
            snapshot.add(new SnapshotEntry(pos, level.getBlockState(pos)));
        }
    }

    private static void captureAndSet(ServerLevel level, BlockPos pos, BlockState desired, List<SnapshotEntry> snapshot, Set<BlockPos> captured) {
        if (level.getBlockState(pos).equals(desired)) {
            return;
        }
        captureOriginal(level, pos, snapshot, captured);
        level.setBlock(pos, desired, 3);
    }

    private static void paveColumn(ServerLevel level, int x, int targetY, int z, int naturalY, int tier, List<SnapshotEntry> snapshot, Set<BlockPos> captured) {
        BlockState roadState = ModBlocks.ROADWAY.get().defaultBlockState().setValue(TieredRoadwayBlock.TIER, tier);
        if (targetY > naturalY) {
            for (int y = naturalY; y < targetY; y++) {
                captureAndSet(level, new BlockPos(x, y, z), roadState, snapshot, captured);
            }
        } else if (targetY < naturalY) {
            for (int y = targetY + 1; y <= naturalY; y++) {
                BlockPos pos = new BlockPos(x, y, z);
                if (!level.getBlockState(pos).isAir()) {
                    captureOriginal(level, pos, snapshot, captured);
                    level.removeBlock(pos, false);
                }
            }
        }
        captureAndSet(level, new BlockPos(x, targetY, z), roadState, snapshot, captured);
        int clearanceStart = Math.max(targetY + 1, naturalY + 1);
        for (int y = clearanceStart; y <= targetY + CLEARANCE_BLOCKS; y++) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.getBlockState(pos).isAir()) {
                captureOriginal(level, pos, snapshot, captured);
                level.removeBlock(pos, false);
            }
        }
    }

    private static int[] slopeLimitedProfile(int[] naturalY, int startY, int endY) {
        int n = naturalY.length;
        int[] profile = new int[n];
        System.arraycopy(naturalY, 0, profile, 0, n);
        profile[0] = startY;
        profile[n - 1] = endY;
        if (n <= 2) {
            return profile;
        }
        int passes = Math.max(8, n * 2);
        for (int pass = 0; pass < passes; pass++) {
            boolean changed = false;
            for (int i = 1; i < n - 1; i++) {
                int clamped = Mth.clamp(profile[i], profile[i - 1] - MAX_SLOPE_PER_STEP, profile[i - 1] + MAX_SLOPE_PER_STEP);
                if (clamped != profile[i]) {
                    profile[i] = clamped;
                    changed = true;
                }
            }
            for (int i = n - 2; i >= 1; i--) {
                int clamped = Mth.clamp(profile[i], profile[i + 1] - MAX_SLOPE_PER_STEP, profile[i + 1] + MAX_SLOPE_PER_STEP);
                if (clamped != profile[i]) {
                    profile[i] = clamped;
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        return profile;
    }
}
