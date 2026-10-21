package com.github.cerealklla.settlemynts.mineshaft;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import com.github.cerealklla.cartographyr.api.Cartography;
import com.github.cerealklla.cartographyr.geo.Geometry;
import com.github.cerealklla.settlemynts.api.Settlemynts;
import com.github.cerealklla.settlemynts.zone.ContainerDeposit;
import com.github.cerealklla.settlemynts.zone.PlotRecord;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;

/**
 * A Mineshaft worker's actual job (added 2026-10-11, explicit user request) -- the first real
 * two-leg round-trip goal in this suite: every existing worker (Farmer/Lumberjack) only ever acts in
 * place and deposits immediately, but a Miner explicitly walks from a random storage chest on the
 * plot out to the mine entrance, then back to that same chest, rolling a 50% chance of a find (via
 * {@link MineLootTable}, scaled by the plot's own Tier) on each round trip.
 *
 * <p>The "mine entrance" waypoint is simply the plot's own {@code PlotRecord#boxPos} (the Building
 * Supply Box, already auto-placed by Blueprynts just outside the structure's own footprint) -- no
 * new anchor/marker concept needed for this.
 */
public class MinerWorkGoal extends Goal {

    private static final int REROLL_CHANCE_DENOMINATOR = 20;
    private static final double ARRIVE_DIST_SQ = 4.0;
    // Distance-scaled, NOT a flat constant -- real bug found via live testing, 2026-10-11: a flat
    // 100-tick (5s) timeout copied from LumberjackWorkGoal (which only ever targets something close
    // by, like a nearby tree) made the Miner give up on its own 40+ block cross-plot round trip
    // before it could possibly arrive, repeatedly -- looked exactly like "aimless wandering" even
    // though distance-to-target was steadily decreasing each attempt (confirmed via debug logging:
    // 1850 -> 610 -> 122 blocks-squared across successive give-ups, each one a real ~5s walk of
    // progress). 40 ticks/block (2s/block, straight-line) is deliberately generous -- live logging
    // also showed actual path node counts well above straight-line block distance (e.g. a 35-node
    // path for an ~11-block gap) once real winding cave terrain is involved, not just open ground.
    private static final int TICKS_PER_BLOCK = 40;
    private static final int MIN_PURSUE_TICKS = 100;
    private static final int MAX_PURSUE_TICKS_CAP = 2400; // 2 minutes, well past any real plot's size.

    private enum Leg {
        OUTBOUND, RETURN
    }

    private final MinerWorkerEntity worker;
    private final double speedModifier;

    private BlockPos chestPos;
    private BlockPos entrancePos;
    private Leg leg;
    private ItemStack pendingLoot = ItemStack.EMPTY;
    private int repathCooldown;
    private int pursueTicks;
    private int pursueTimeoutTicks;

    public MinerWorkGoal(MinerWorkerEntity worker, double speedModifier) {
        this.worker = worker;
        this.speedModifier = speedModifier;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    // Temporary instrumentation (2026-10-21) -- see diagnostics.TickProfiler's own doc.
    @Override
    public boolean canUse() {
        return com.github.cerealklla.settlemynts.diagnostics.TickProfiler.timeValue("MinerWorkGoal.canUse", this::canUseInternal);
    }

    private boolean canUseInternal() {
        if (!worker.getNavigation().isDone()) {
            return false;
        }
        if (worker.getRandom().nextInt(REROLL_CHANCE_DENOMINATOR) != 0) {
            return false;
        }
        if (!(worker.level() instanceof ServerLevel level)) {
            return false;
        }
        Optional<PlotRecord> plot = worker.resolvePlotRecord(level);
        if (plot.isEmpty() || plot.get().boxPos().isEmpty()) {
            return false; // No known mine entrance to work toward.
        }
        // Real fix, 2026-10-21: live TickProfiler data showed this call alone costing 100-130ms,
        // repeatedly -- resolvePlotBoxPositions(level, plotId) re-does a full-world
        // GhostTownHallCoreEntity scan from scratch (see Settlemynts#findPolygonForPlot) even though
        // this goal already has the plot's own PlotRecord in hand two lines up. Using
        // resolvePolygonDirect (a cheap per-entity-id lookup, no world scan) plus the matching
        // polygon-based overload closes the same "resolve once, reuse" gap already fixed elsewhere.
        Optional<Geometry.Polygon> polygon = Settlemynts.resolvePolygonDirect(level, plot.get());
        List<BlockPos> boxPositions = polygon.map(p -> Settlemynts.resolveBoxPositionsForPolygon(level, p)).orElse(List.of());
        if (boxPositions.isEmpty()) {
            return false; // No storage chest on the plot to work from.
        }
        chestPos = boxPositions.get(worker.getRandom().nextInt(boxPositions.size()));
        entrancePos = plot.get().boxPos().get();
        leg = Leg.OUTBOUND;
        pendingLoot = ItemStack.EMPTY;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return leg != null;
    }

    @Override
    public void start() {
        repathCooldown = 0;
        pursueTicks = 0;
        pursueTimeoutTicks = timeoutFor(entrancePos);
        moveToCurrentTarget();
    }

    private int timeoutFor(BlockPos target) {
        double distance = Math.sqrt(worker.blockPosition().distSqr(target));
        return (int) Math.max(MIN_PURSUE_TICKS, Math.min(MAX_PURSUE_TICKS_CAP, distance * TICKS_PER_BLOCK));
    }

    @Override
    public void stop() {
        leg = null;
        chestPos = null;
        entrancePos = null;
        pendingLoot = ItemStack.EMPTY;
    }

    // Temporary instrumentation (2026-10-21) -- see diagnostics.TickProfiler's own doc.
    @Override
    public void tick() {
        com.github.cerealklla.settlemynts.diagnostics.TickProfiler.time("MinerWorkGoal.tick", this::tickInternal);
    }

    private void tickInternal() {
        if (leg == null || !(worker.level() instanceof ServerLevel level)) {
            return;
        }
        BlockPos target = leg == Leg.OUTBOUND ? entrancePos : chestPos;
        double currentDistSq = worker.blockPosition().distSqr(target);
        if (currentDistSq > ARRIVE_DIST_SQ) {
            if (++pursueTicks > pursueTimeoutTicks) {
                leg = null; // Give up this trip entirely -- canUse will pick a fresh chest/attempt next reroll.
                return;
            }
            if (worker.getNavigation().isDone() && repathCooldown-- <= 0) {
                moveToCurrentTarget();
                repathCooldown = 20;
            }
            return;
        }

        if (leg == Leg.OUTBOUND) {
            pendingLoot = MineLootTable.rollLoot(worker.resolvePlotRecord(level).map(PlotRecord::tier).orElse(1), worker.getRandom())
                    .orElse(ItemStack.EMPTY);
            leg = Leg.RETURN;
            pursueTicks = 0;
            pursueTimeoutTicks = timeoutFor(chestPos);
            moveToCurrentTarget();
            return;
        }

        if (!pendingLoot.isEmpty()) {
            Container chest = Cartography.getBoxIdAt(level, chestPos)
                    .flatMap(boxId -> Cartography.resolveBoxContainer(level, boxId))
                    .orElse(null);
            if (chest != null) {
                ContainerDeposit.depositIntoAny(List.of(chest), pendingLoot);
            }
        }
        leg = null; // Round trip complete -- canUse picks the next one.
    }

    private void moveToCurrentTarget() {
        BlockPos target = leg == Leg.OUTBOUND ? entrancePos : chestPos;
        worker.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, speedModifier);
    }
}
