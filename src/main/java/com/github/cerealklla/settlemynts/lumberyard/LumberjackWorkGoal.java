package com.github.cerealklla.settlemynts.lumberyard;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.github.cerealklla.cartographyr.geo.Geometry;
import com.github.cerealklla.settlemynts.api.Settlemynts;
import com.github.cerealklla.settlemynts.zone.ContainerDeposit;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * A Lumberyard worker's actual job (added 2026-10-05, explicit user request) -- one self-contained
 * state-machine {@link Goal} (this codebase's established "one goal class per behavior" convention,
 * not a generic engine), mirroring {@code resident.ResidentPatrolGoal}'s polygon-confined-movement
 * shape but adding a real arrive-then-act step. Harvesting takes priority over planting whenever both
 * are available.
 *
 * <p>Both the harvest-tree-base detection and the plant-spot/spacing checks are derived entirely from
 * live world state (no persisted "I planted this" memory) -- see {@code LumberjackWorkerEntity}'s own
 * class doc and this session's plan for why: a fresh worker respawning after death must still find
 * real work correctly with no memory of what the previous instance did.
 *
 * <p><b>"One lap per day" removed, 2026-10-06</b> (explicit user request: back to constantly working
 * -- the day-gate was observed getting workers stuck/stopped trying) -- {@code canUse} now just
 * keeps re-scanning for work on every reroll indefinitely, the same way it did before that gate
 * existed. {@code LumberjackWorkerEntity}'s {@code lastWorkDay}/{@code lapComplete} fields are gone
 * along with it.
 *
 * <p><b>Give-up timeout added, 2026-10-06</b> (real live bug: a worker was found permanently frozen
 * on Production, confirmed stationary for 20+ seconds via RCON while a different worker nearby kept
 * moving normally) -- root cause was this goal having no way to abandon an unreachable target: once
 * {@link #pendingTarget} was set, {@code canContinueToUse} returned {@code true} forever and {@code
 * tick} just kept re-issuing the same {@code moveTo} every 20 ticks indefinitely, with nothing ever
 * letting a fresh scan run. The user independently confirmed the live case was a trunk whose only
 * approach was blocked by its own leaves (motivating the "harvest breaks leaves too" change above,
 * already in place -- but that only helps once the worker *arrives*, not while it's still failing to
 * path there at all). {@code tick} now counts {@link #pursueTicks} while not yet arrived; past {@link
 * #MAX_PURSUE_TICKS} it gives up, remembers the target in {@link #recentlyAvoided} for {@link
 * #AVOID_DURATION_TICKS}, and clears {@code pendingTarget} so the next {@code canUse} scan is free to
 * pick something else (or the same tree again once the avoidance window expires, by which point the
 * leaves blocking it may well be gone from other activity). {@code findTarget} skips anything within
 * {@link #AVOID_RADIUS_BLOCKS} of a still-active avoided entry rather than exact-position matching,
 * since a wide (e.g. 2x2 dark oak) trunk resolves to a different base column per side.
 */
public class LumberjackWorkGoal extends Goal {

    private static final int REROLL_CHANCE_DENOMINATOR = 20;
    private static final double ARRIVE_DIST_SQ = 4.0;
    // Harvest-only, widened 2026-10-06 (real live report: "teleported on top of a tree but isn't
    // chopping it down") -- root cause is LumberjackWorkerEntity#customServerAiStep's underground-
    // escape safety net, which measures ground level via Heightmap.Types.MOTION_BLOCKING_NO_LEAVES:
    // that excludes leaves but NOT logs, so standing at a tall trunk's own column reads as
    // "underground" relative to the trunk's own top and gets teleported up onto it. The flat 2-block
    // arrive radius then never counted as "arrived" at the trunk base once perched up there. harvest()
    // acts purely on the stored world position, not the worker's own, so it's safe to just widen how
    // close counts as arrived for a harvest target specifically -- 15 blocks comfortably covers
    // standing anywhere on even the tallest trunk this goal will ever climb (MAX_TRUNK_HEIGHT + canopy
    // margin). Deliberately NOT applied to planting too -- there's no equivalent teleport-onto-target
    // failure mode for a ground-level soil spot, and a wide plant-arrive radius would make the worker
    // plant from well outside the spot it's supposedly walking to.
    private static final double HARVEST_ARRIVE_DIST_SQ = 225.0;
    // Randomized per scan (2026-10-05, explicit user request: "5-7 instead of flat 4") -- varies the
    // plot's planting pattern instead of a perfectly uniform grid.
    private static final int MIN_SAPLING_SPACING_BLOCKS = 5;
    private static final int MAX_SAPLING_SPACING_BLOCKS = 7;
    private static final int MAX_COLUMNS_SCANNED = 400; // bounded scan -- see class doc, not meant for huge polygons.
    private static final Set<Block> PLANTABLE_SOIL = Set.of(
            net.minecraft.world.level.block.Blocks.GRASS_BLOCK, net.minecraft.world.level.block.Blocks.DIRT,
            net.minecraft.world.level.block.Blocks.COARSE_DIRT, net.minecraft.world.level.block.Blocks.PODZOL,
            net.minecraft.world.level.block.Blocks.MYCELIUM, net.minecraft.world.level.block.Blocks.ROOTED_DIRT);

    private static final int MAX_PURSUE_TICKS = 100; // ~5s of real failure to arrive -- give up.
    private static final long AVOID_DURATION_TICKS = 1200; // 1 minute before retrying the same spot.
    private static final double AVOID_RADIUS_BLOCKS = 3.0;

    private final LumberjackWorkerEntity worker;
    private final double speedModifier;

    private BlockPos pendingTarget;
    private boolean pendingIsHarvest;
    private int repathCooldown;
    private int pursueTicks;
    private final List<AvoidedTarget> recentlyAvoided = new ArrayList<>();

    private record AvoidedTarget(BlockPos pos, long expiresAtGameTime) {
    }

    public LumberjackWorkGoal(LumberjackWorkerEntity worker, double speedModifier) {
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
        double arriveDistSq = pendingIsHarvest ? HARVEST_ARRIVE_DIST_SQ : ARRIVE_DIST_SQ;
        if (worker.blockPosition().distSqr(pendingTarget) > arriveDistSq) {
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
        if (pendingIsHarvest) {
            harvest(level, pendingTarget);
        } else {
            plant(level, pendingTarget);
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

        int spacing = MIN_SAPLING_SPACING_BLOCKS + worker.getRandom().nextInt(MAX_SAPLING_SPACING_BLOCKS - MIN_SAPLING_SPACING_BLOCKS + 1);
        int scanned = 0;
        BlockPos firstPlantCandidate = null;
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
                if (topState.is(BlockTags.LOGS)) {
                    BlockPos base = findTrunkBase(level, topSolid);
                    if (isRealTree(level, base) && !isAvoided(level, base)) {
                        return new WorkTarget(base, true); // Harvest always wins immediately once found.
                    }
                    // Not a real tree (no leaves anywhere nearby) -- almost certainly a player-built
                    // log structure (2026-10-05, real report: "destroyed the building to try to plant
                    // trees" -- this is the actual fix, see isRealTree's own doc). Skip it entirely,
                    // don't treat it as a planting spot either.
                    continue;
                }
                if (firstPlantCandidate == null && PLANTABLE_SOIL.contains(topState.getBlock())
                        && level.getBlockState(topSolid.above()).canBeReplaced()
                        && !hasNearbyTreeOrSapling(level, area, x, topSolid.getY(), z, spacing)
                        && !isAvoided(level, topSolid.above())) {
                    firstPlantCandidate = topSolid.above();
                }
            }
        }
        return firstPlantCandidate == null ? null : new WorkTarget(firstPlantCandidate, false);
    }

    /** Walks straight down through a vertical log column to its lowest (ground-level) block. */
    private static BlockPos findTrunkBase(ServerLevel level, BlockPos topLog) {
        BlockPos pos = topLog;
        while (pos.getY() > level.getMinY() && level.getBlockState(pos.below()).is(BlockTags.LOGS)) {
            pos = pos.below();
        }
        return pos;
    }

    /**
     * Checks a direct vertical band around the candidate's own ground level ({@code baseY}) for an
     * existing sapling or tree in every neighboring column within the spacing radius -- deliberately
     * NOT heightmap-based (fixed 2026-10-05, real report: "planting trees right next to each other,
     * not spaced 4 away"). A {@code SaplingBlock} has no collision, so {@code
     * Heightmap.Types.MOTION_BLOCKING_NO_LEAVES} treats it as transparent and reports the *soil*
     * underneath as the column's top instead -- the original heightmap-based check could therefore
     * never actually see a freshly-planted, not-yet-grown sapling at all, only a fully-grown tree's
     * logs (which genuinely are solid). Scanning a fixed band directly (ground level up through a
     * young tree's own height) catches both cases reliably, assuming the plot's own terrain is
     * reasonably flat near the candidate -- same approximation this whole feature already makes.
     */
    private static boolean hasNearbyTreeOrSapling(ServerLevel level, Geometry.Polygon area, int x, int baseY, int z, int spacing) {
        for (int dx = -spacing; dx <= spacing; dx++) {
            for (int dz = -spacing; dz <= spacing; dz++) {
                if (dx == 0 && dz == 0 || dx * dx + dz * dz > spacing * spacing) {
                    continue;
                }
                int nx = x + dx;
                int nz = z + dz;
                if (!area.contains(nx, nz)) {
                    continue;
                }
                for (int dy = -1; dy <= 6; dy++) {
                    BlockState state = level.getBlockState(new BlockPos(nx, baseY + dy, nz));
                    if (state.is(BlockTags.LOGS) || state.is(BlockTags.SAPLINGS)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // How far a tree's own branches/canopy can plausibly extend from its trunk column -- bounds
    // both isRealTree's leaf search and harvest's own break region. Deliberately small and
    // *position*-bounded, not connectivity-bounded -- see both methods' own doc for why.
    private static final int CANOPY_HORIZONTAL_RADIUS = 2;
    private static final int CANOPY_VERTICAL_MARGIN = 3;
    private static final int MAX_TRUNK_HEIGHT = 12; // generously covers every vanilla tree's trunk.

    /** Walks straight up through a vertical log column to find where the trunk ends. */
    private static BlockPos trunkTop(ServerLevel level, BlockPos base) {
        BlockPos pos = base;
        int climbed = 0;
        while (climbed < MAX_TRUNK_HEIGHT && level.getBlockState(pos.above()).is(BlockTags.LOGS)) {
            pos = pos.above();
            climbed++;
        }
        return pos;
    }

    /**
     * Checks whether a discovered trunk is a real, naturally-grown tree (has leaves somewhere in its
     * own small canopy area) rather than a player-built log structure -- fixed 2026-10-05, real
     * report: "destroyed the building to try to plant trees." A log cabin wall is still just {@code
     * BlockTags.LOGS} blocks, indistinguishable from a trunk by block type alone; real vanilla trees
     * always generate with leaves attached, buildings essentially never do.
     *
     * <p><b>Deliberately a fixed local bounding box around the trunk column, not a connectivity-based
     * flood-fill</b> -- a flood-fill following every connected log would also follow straight from a
     * real tree's trunk into an adjacent building's log wall if the two happen to touch, validating
     * (and, worse, later harvesting -- see {@link #harvest}) the *entire* connected mass as one "real
     * tree" the instant it found a single leaf anywhere in that merged cluster. A small box anchored
     * to the trunk's own position can never reach that far regardless of what's connected to what.
     */
    private static boolean isRealTree(ServerLevel level, BlockPos base) {
        BlockPos top = trunkTop(level, base);
        for (int x = base.getX() - CANOPY_HORIZONTAL_RADIUS; x <= base.getX() + CANOPY_HORIZONTAL_RADIUS; x++) {
            for (int z = base.getZ() - CANOPY_HORIZONTAL_RADIUS; z <= base.getZ() + CANOPY_HORIZONTAL_RADIUS; z++) {
                for (int y = base.getY(); y <= top.getY() + CANOPY_VERTICAL_MARGIN; y++) {
                    if (level.getBlockState(new BlockPos(x, y, z)).is(BlockTags.LEAVES)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void plant(ServerLevel level, BlockPos pos) {
        // Live re-check (2026-10-05, real report: NPCs destroying non-soil/solid blocks while
        // planting) -- never overwrite anything that isn't genuinely clearable (grass, tall grass,
        // snow layers, etc. are all fine per explicit user instruction; a real solid block is not),
        // and re-verify the ground below is still actual plantable soil, not just whatever the scan
        // saw some ticks ago.
        if (!level.getBlockState(pos).canBeReplaced()) {
            return;
        }
        if (!PLANTABLE_SOIL.contains(level.getBlockState(pos.below()).getBlock())) {
            return;
        }
        Item sapling = LumberjackSaplingSpecies.localSpeciesSapling(level, pos);
        Block saplingBlock = Block.byItem(sapling);
        if (saplingBlock != net.minecraft.world.level.block.Blocks.AIR) {
            level.setBlock(pos, saplingBlock.defaultBlockState(), 3);
        }
    }

    // Bounds the log flood-fill's worst case, mirroring Lyfe's own Lumberjack-skill cap exactly
    // (gathering.WholeStructureClear#MAX_BLOCKS) -- "comfortably larger than any vanilla tree," and
    // the same accepted tradeoff that cap already makes: a flood-fill could in principle walk from a
    // real tree's trunk into an adjacent player-built log structure if the two happen to touch, but
    // isRealTree already gates entry (a cabin wall alone never qualifies), and this cap bounds how far
    // into anything connected the clear can ever reach regardless.
    private static final int MAX_CONNECTED_LOGS = 64;

    /**
     * Flood-fills every log connected to {@code base} (26-neighbor, matching {@code BlockTags.LOGS})
     * instead of the old fixed local box -- added 2026-10-09, real report (screenshot): wide/branchy
     * trees were left with floating logs after a harvest, since the previous box was sized around just
     * the base/top trunk column and a real tree's branches can extend well outside that. This is the
     * exact same algorithm the Lumberjack *skill* already uses for a player's own tree-chop ({@code
     * lyfe.gathering.WholeStructureClear#connectedBlocksOf}, including its 26-neighbor offsets for
     * diagonally-offset branch logs and its same safety cap) -- duplicated here in full rather than
     * taken as a dependency, since Settlemynts' worker AI must keep working correctly on a server with
     * no Lyfe installed at all.
     */
    private static Set<BlockPos> findConnectedLogs(ServerLevel level, BlockPos base) {
        Set<BlockPos> found = new java.util.HashSet<>();
        java.util.Deque<BlockPos> frontier = new java.util.ArrayDeque<>();
        frontier.add(base);
        found.add(base);
        while (!frontier.isEmpty() && found.size() < MAX_CONNECTED_LOGS) {
            BlockPos current = frontier.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos neighbor = current.offset(dx, dy, dz);
                        if (found.contains(neighbor)) {
                            continue;
                        }
                        if (level.getBlockState(neighbor).is(BlockTags.LOGS)) {
                            found.add(neighbor);
                            frontier.add(neighbor);
                            if (found.size() >= MAX_CONNECTED_LOGS) {
                                break;
                            }
                        }
                    }
                }
            }
        }
        return found;
    }

    /**
     * Breaks every connected log (flood-filled, see {@link #findConnectedLogs}) plus every leaf within
     * a margin of the real discovered trunk/branch footprint -- reworked 2026-10-09 from the old fixed
     * local box (see {@link #findConnectedLogs}'s own doc for why). The leaf box is now the bounding
     * box of every log actually found, expanded by {@link #CANOPY_HORIZONTAL_RADIUS}/{@link
     * #CANOPY_VERTICAL_MARGIN}, instead of a box anchored only at the trunk's own base/top column --
     * the same floating-canopy bug would otherwise persist for leaves even after the logs themselves
     * were fully cleared.
     *
     * <p>Leaves now roll their real loot table (explicit user request, 2026-10-09: "pick up anything
     * dropped by the tree... I would expect to see Apples and stick too (just not saplings)") via
     * {@link Block#getDrops}, the same real-loot-table call {@code construction.SiteTerrainOps} already
     * uses elsewhere in the suite for an identical "no player/tool context" case -- this naturally
     * covers vanilla's own stick drop chance (added to every leaf type) and Oak's extra apple chance,
     * with no hand-authored drop table needed. Sapling drops are deliberately filtered out and
     * discarded (not deposited, not even dropped as a loose item) per that same explicit instruction --
     * the Lumberjack already plants fresh saplings itself via {@link #plant}, so handing back the ones
     * it just broke would be redundant at best.
     */
    private void harvest(ServerLevel level, BlockPos base) {
        Set<BlockPos> logs = findConnectedLogs(level, base);
        int minX = base.getX(), maxX = base.getX();
        int minY = base.getY(), maxY = base.getY();
        int minZ = base.getZ(), maxZ = base.getZ();
        List<Container> boxes = null;
        for (BlockPos pos : logs) {
            minX = Math.min(minX, pos.getX());
            maxX = Math.max(maxX, pos.getX());
            minY = Math.min(minY, pos.getY());
            maxY = Math.max(maxY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxZ = Math.max(maxZ, pos.getZ());
            Item logItem = level.getBlockState(pos).getBlock().asItem();
            level.removeBlock(pos, false);
            if (logItem != net.minecraft.world.item.Items.AIR) {
                if (boxes == null) {
                    boxes = Settlemynts.resolvePlotBoxes(level, worker.plotId());
                }
                if (!boxes.isEmpty()) {
                    ContainerDeposit.depositIntoAny(boxes, new ItemStack(logItem, 1));
                }
            }
        }

        for (int x = minX - CANOPY_HORIZONTAL_RADIUS; x <= maxX + CANOPY_HORIZONTAL_RADIUS; x++) {
            for (int z = minZ - CANOPY_HORIZONTAL_RADIUS; z <= maxZ + CANOPY_HORIZONTAL_RADIUS; z++) {
                for (int y = minY; y <= maxY + CANOPY_VERTICAL_MARGIN; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState leafState = level.getBlockState(pos);
                    if (!leafState.is(BlockTags.LEAVES)) {
                        continue;
                    }
                    List<ItemStack> drops = Block.getDrops(leafState, level, pos, null);
                    level.removeBlock(pos, false);
                    for (ItemStack drop : drops) {
                        if (drop.isEmpty() || drop.is(net.minecraft.tags.ItemTags.SAPLINGS)) {
                            continue;
                        }
                        if (boxes == null) {
                            boxes = Settlemynts.resolvePlotBoxes(level, worker.plotId());
                        }
                        if (!boxes.isEmpty()) {
                            ContainerDeposit.depositIntoAny(boxes, drop);
                        }
                    }
                }
            }
        }
    }
}
