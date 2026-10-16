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
import com.github.cerealklla.cartographyr.geo.EntityId;
import com.github.cerealklla.cartographyr.geo.EntityType;
import com.github.cerealklla.cartographyr.geo.GeographicEntity;
import com.github.cerealklla.cartographyr.geo.Geometry;
import com.github.cerealklla.cartographyr.geo.Layer;
import com.github.cerealklla.cartographyr.geo.LifecycleState;
import com.github.cerealklla.settlemynts.SettlemyntsMod;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.registration.ModBlocks;
import com.github.cerealklla.settlemynts.zone.PlotGeometry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
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
    // "Within 10 of the direct line between stakes" -- explicit user spec for Highway tunnel
    // protection, 2026-10-13. See bridge.ProtectyonsPlotBridge#registerHighwayTunnel's own doc.
    private static final double TUNNEL_PROTECTION_RADIUS_BLOCKS = 10.0;

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

    // See RoadwayPaver#naturalSurfaceYNear's own doc for the full real-live-report writeup -- this is
    // the identical fix, duplicated here per this class's own "copy and tweak" precedent rather than
    // shared, same as every other piece of this file.
    private static final int SURFACE_SEARCH_RADIUS_BLOCKS = 24;

    private static int naturalSurfaceYNear(ServerLevel level, int x, int z, int referenceY) {
        int minY = level.getMinY();
        int maxY = level.getMaxY();
        for (int d = 0; d <= SURFACE_SEARCH_RADIUS_BLOCKS; d++) {
            int up = referenceY + d;
            if (up <= maxY && isWalkableSurface(level, x, up, z)) {
                return up;
            }
            if (d > 0) {
                int down = referenceY - d;
                if (down >= minY && isWalkableSurface(level, x, down, z)) {
                    return down;
                }
            }
        }
        return naturalSurfaceY(level, x, z);
    }

    private static boolean isWalkableSurface(ServerLevel level, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        return isSolid(level, pos) && !isSolid(level, pos.above());
    }

    /**
     * True if {@code pos} holds a block with real collision -- explicit user request, 2026-10-16:
     * "they should completely ignore any non-solid blocks." Leaf litter (and similarly tall grass,
     * flowers, saplings, etc.) is a real, non-air {@link BlockState} but has no collision at all
     * ({@code noCollision()} in its own vanilla registration) -- a plain {@code isAir()} check was
     * wrongly treating it as solid ground in {@link #isWalkableSurface} (producing a non-flat road
     * surface wherever it happened to carpet the real ground underneath) and as a real obstruction
     * needing clearing in {@code paveColumn}'s cut/clearance loops (needless extra work for every
     * decorative block in the clearance band, none of which actually blocks anything). See the
     * identical fix in {@code RoadwayPaver}'s own doc.
     */
    private static boolean isSolid(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && !state.getCollisionShape(level, pos).isEmpty();
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

    /**
     * True if {@code (x, z)} falls inside ANY settlement's real, unpadded boundary -- player-founded
     * or natural. Added 2026-10-13, real live-test report: a natural (NPC) village has no {@link
     * GhostTownHallCoreEntity} at all, so {@link #isInsideSettlement} (which requires one) never
     * blocked placement inside one, letting a Highway Stake be planted fully inside an NPC town --
     * "not necessarily a problem" per the user's own framing, but still a real gap worth closing,
     * especially since it covers every settlement along the route, not just the quest's own two named
     * endpoints (an unrelated settlement passed over elsewhere along the path should block the same
     * way). A player-founded settlement's own entry here is its padded {@code EntityType.SETTLEMENT}
     * buffer AND its tighter {@code SettlemyntsMod#SETTLEMENT_CORE_ENTITY_TYPE} core as two separate
     * entities at the same point -- only the real core (or a lone polygon with no core at all, i.e. a
     * natural village) should block; the padded-only case is deliberately let through, matching {@link
     * #isInsideSettlement}'s own "No Man's Land stays placeable" reasoning.
     */
    public static boolean isInsideAnySettlement(ServerLevel level, int x, int z) {
        for (GeographicEntity entity : Cartography.getEntitiesAt(level, x, z)) {
            if (!entity.layerId().equals(Layer.SETTLEMENT_ID) || entity.lifecycleState() != LifecycleState.REALIZED) {
                continue;
            }
            if (entity.type().equals(SettlemyntsMod.SETTLEMENT_CORE_ENTITY_TYPE)) {
                return true;
            }
            if (entity.type().equals(EntityType.SETTLEMENT)
                    && GhostTownHallCoreEntity.findByCartographyrEntityId(level, entity.id().value()).isEmpty()) {
                return true; // No core registered for this one -- a natural village's own real boundary.
            }
        }
        return false;
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
        int startY = Mth.floor(a.getY()) - 1;
        int endY = Mth.floor(b.getY()) - 1;
        int[] naturalY = new int[n];
        for (int i = 0; i < n; i++) {
            Geometry.Polygon.Vertex v = centerline.get(i);
            // Reference height for the surface search -- the straight-line interpolation between the
            // two stakes' own ground level at this point, so a tunnel dug under a hill is found
            // instead of the hill's distant outer surface. See RoadwayPaver#naturalSurfaceYNear's own
            // doc for the full real-live-report writeup (identical fix, this class's own copy).
            int referenceY = n <= 1 ? startY : Math.round(startY + (endY - startY) * (i / (float) (n - 1)));
            naturalY[i] = naturalSurfaceYNear(level, v.x(), v.z(), referenceY);
        }
        int[] profile = slopeLimitedProfile(medianSmooth(naturalY), startY, endY);

        List<SnapshotEntry> snapshot = new ArrayList<>();
        List<BlockPos> roadCells = new ArrayList<>();
        Set<BlockPos> captured = new HashSet<>();
        // See RoadwayPaver#pavedColumns' own doc -- identical fix, same real live report ("for
        // angled roads it seems to be determining that height from the highest point of the road
        // and using it along the entire length"): a direction-change in supercoverLine's zigzag
        // (far more frequent on an angled edge than a cardinal one, which never changes direction)
        // can make a later centerline index's cross-section re-pave a column an earlier index
        // already claimed, silently overwriting its height. First-touch-wins keeps it deterministic.
        Set<BlockPos> pavedColumns = new HashSet<>();
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

            // Ported from RoadwayPaver 2026-10-13 (explicit user request: "need to behave the same
            // with highway stakes too") -- see that class's #stairStateFor/#paveColumn own docs for
            // the full real-live-report writeup, including the base-Y derivation (a stair's base must
            // equal the HIGHER of the two flat landings it bridges, not the lower one -- confirmed by
            // direct RCON inspection of a live placed stair's FACING + position). Identical logic,
            // this class's own copy per convention.
            BlockState stepState = null;
            if (i > 0 && profile[i] != profile[i - 1]) {
                Direction travelDirection = directionFromDelta(v.x() - centerline.get(i - 1).x(), v.z() - centerline.get(i - 1).z());
                if (profile[i] < profile[i - 1]) {
                    travelDirection = travelDirection.getOpposite();
                }
                stepState = stairStateFor(tier, travelDirection);
            }
            int pavedY = stepState != null ? Math.max(profile[i], profile[i - 1]) : profile[i];

            for (int offset = -ROAD_SIDE_OFFSET_BLOCKS; offset <= ROAD_SIDE_OFFSET_BLOCKS; offset++) {
                int colX = v.x() + perpX * offset;
                int colZ = v.z() + perpZ * offset;
                if (offset != 0 && core != null && PlotGeometry.findContainingPlot(level, core, colX, colZ).isPresent()) {
                    continue;
                }
                BlockPos columnKey = new BlockPos(colX, 0, colZ);
                if (!pavedColumns.add(columnKey)) {
                    continue; // Already paved by an earlier (nearer) centerline index -- see pavedColumns' own doc.
                }
                int targetY = pavedY;
                int natural = offset == 0 ? naturalY[i] : naturalSurfaceYNear(level, colX, colZ, targetY);
                paveColumn(level, colX, targetY, colZ, natural, tier, stepState, snapshot, captured);
                roadCells.add(new BlockPos(colX, targetY, colZ));
            }
        }

        var roadEntity = Cartography.createEntity(level, new EntityDefinition(
                level.dimension(), Classification.CONSTRUCTED, EntityType.ROAD, Layer.ROADWAY_ID,
                Optional.empty(), new Geometry.Path(centerline, PATH_HALF_WIDTH_BLOCKS), LifecycleState.REALIZED, Optional.empty()));
        a.setSnapshotFor(b.getUUID(), snapshot, roadEntity.id(), roadCells);
        b.setSnapshotFor(a.getUUID(), snapshot, roadEntity.id(), roadCells);

        // Highway tunnel protection is deliberately NOT registered here -- real live report,
        // 2026-10-13: "you are making terrain uneditable BEFORE the road is finalized, which is
        // wrong." Registering it per-edge, the instant each individual connection paves, protected
        // a Highway quest's own still-under-construction road against the very player building it
        // (and anyone else) long before the two settlements were actually connected. Protection is
        // now registered once, for the whole completed path at once, only once the road-connection
        // quest genuinely finishes -- see HighwayQuestSupport#registerTunnelProtectionForPath,
        // called from #checkProgress the instant it confirms a real connected path.

        SettlemyntsMod.LOGGER.info("[Highway] Added road: {} centerline cells, {} snapshot entries captured", n, snapshot.size());
    }

    /** Real 3D tunnel protection for one already-paved edge -- {@code roadEntityId} doubles as the tunnel segment's own id (see {@link #paveEdge}'s own doc), so cleanup (snapAllConnections/despawnAllForInstance) needs no extra tracking beyond what {@code HighwayStakeEntity.ConnectionSnapshot} already carries. No-op if Protectyons isn't loaded. */
    public static void registerTunnelProtection(ServerLevel level, EntityId roadEntityId, net.minecraft.world.phys.Vec3 posA, net.minecraft.world.phys.Vec3 posB) {
        if (net.neoforged.fml.ModList.get().isLoaded("protectyons")) {
            com.github.cerealklla.settlemynts.bridge.ProtectyonsPlotBridge.registerHighwayTunnel(level, roadEntityId.value(), posA, posB, TUNNEL_PROTECTION_RADIUS_BLOCKS);
        }
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

    /** Ported from RoadwayPaver -- see that class's own doc. Tier 3/4 only; other tiers have no real vanilla stairs match and keep their flat-step look. */
    static BlockState stairStateFor(int tier, Direction facing) {
        net.minecraft.world.level.block.Block stairBlock = switch (tier) {
            case 3 -> Blocks.STONE_BRICK_STAIRS;
            case 4 -> Blocks.BRICK_STAIRS;
            default -> null;
        };
        if (stairBlock == null) {
            return null;
        }
        return stairBlock.defaultBlockState()
                .setValue(StairBlock.FACING, facing)
                .setValue(StairBlock.HALF, Half.BOTTOM);
    }

    /** Ported from RoadwayPaver -- see that class's own doc. */
    private static Direction directionFromDelta(int dx, int dz) {
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static void paveColumn(ServerLevel level, int x, int targetY, int z, int naturalY, int tier, BlockState stepState, List<SnapshotEntry> snapshot, Set<BlockPos> captured) {
        BlockState roadState = stepState != null ? stepState : ModBlocks.ROADWAY.get().defaultBlockState().setValue(TieredRoadwayBlock.TIER, tier);
        if (targetY > naturalY) {
            for (int y = naturalY; y < targetY; y++) {
                captureAndSet(level, new BlockPos(x, y, z), roadState, snapshot, captured);
            }
        } else if (targetY < naturalY) {
            // Capped to the same clearance band as every other column (fixed 2026-10-12) -- see
            // RoadwayPaver#paveColumn's own doc for the full real-live-report root cause.
            int cutTop = Math.min(naturalY, targetY + CLEARANCE_BLOCKS);
            for (int y = targetY + 1; y <= cutTop; y++) {
                BlockPos pos = new BlockPos(x, y, z);
                if (isSolid(level, pos)) {
                    captureOriginal(level, pos, snapshot, captured);
                    level.removeBlock(pos, false);
                }
            }
        }
        captureAndSet(level, new BlockPos(x, targetY, z), roadState, snapshot, captured);
        int clearanceStart = Math.max(targetY + 1, naturalY + 1);
        for (int y = clearanceStart; y <= targetY + CLEARANCE_BLOCKS; y++) {
            BlockPos pos = new BlockPos(x, y, z);
            if (isSolid(level, pos)) {
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
