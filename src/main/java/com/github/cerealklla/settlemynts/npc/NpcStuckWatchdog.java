package com.github.cerealklla.settlemynts.npc;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;

/**
 * Generic "stuck -> go home" safety net shared by every custom ground NPC in this suite (Guard,
 * Resident, Farmer, Lumberjack, Miner), 2026-10-22 -- replaces {@code NpcTreetopEscape} and each
 * entity's own bespoke underground/treetop/embedded-in-solid rescue logic entirely. Explicit user
 * pivot after several real live reports of those heightmap-scan-based safety nets misfiring and
 * teleporting an NPC ONTO a building's roof or a tree's own canopy -- the literal thing they were
 * built to rescue an NPC FROM. Every one of them ultimately came down to "where is the real
 * ground/surface here?" being a surprisingly hard question to answer robustly from a block-state
 * scan alone (a branch, an overhang, a roof all read as "the surface" to one heightmap type or
 * another, and each fix for one false positive tended to open another).
 *
 * <p>This asks a completely different, much simpler question instead: "has this NPC actually gone
 * anywhere while it's supposedly trying to get somewhere?" -- no block-state scanning, no
 * surface-guessing, and a rescue destination that's always a real, already-known-safe position (the
 * NPC's own spawn point -- a Guard's assigned stand, a Villager worker's Plot Config Sign) rather
 * than one freshly computed on the spot, which is exactly what kept going wrong before.
 *
 * <p>One instance per NPC (not static) -- each needs its own sampled position/streak state. Call
 * {@link #tick} once per server AI step; a {@code null} spawn position (not yet assigned by the
 * owning spawn ticker) is a no-op. This is a coarse OUTER safety net sitting on top of each goal's
 * own faster per-target retry/give-up logic (e.g. {@code LumberjackWorkGoal}'s own ~5s pursuit
 * timeout before trying a different target) -- it only fires once an NPC has made no real progress
 * at all across several consecutive samples, regardless of how many different targets its own goals
 * cycled through in the meantime.
 */
public final class NpcStuckWatchdog {

    private static final int SAMPLE_INTERVAL_TICKS = 60; // 3 seconds between progress checks.
    // "Didn't really go anywhere" -- generous enough that normal walking speed across one sample
    // window clears it easily; only a genuinely wedged/looping NPC stays under this.
    private static final double MIN_PROGRESS_DISTANCE_SQ = 4.0; // 2 blocks.
    // Consecutive stalled samples, all while actively navigating somewhere, before rescuing --
    // ~12 seconds of confirmed zero progress, not a single unlucky sample.
    private static final int MAX_CONSECUTIVE_STALLS = 4;

    private double lastX;
    private double lastY;
    private double lastZ;
    private boolean sampled;
    private int ticksSinceSample;
    private int consecutiveStalls;

    public void tick(Mob npc, BlockPos spawnPos) {
        if (spawnPos == null) {
            return;
        }
        if (++ticksSinceSample < SAMPLE_INTERVAL_TICKS) {
            return;
        }
        ticksSinceSample = 0;

        if (!sampled) {
            sampled = true;
            recordPosition(npc);
            return;
        }

        boolean activelyNavigating = !npc.getNavigation().isDone();
        if (activelyNavigating && npc.distanceToSqr(lastX, lastY, lastZ) < MIN_PROGRESS_DISTANCE_SQ) {
            consecutiveStalls++;
        } else {
            consecutiveStalls = 0;
        }
        recordPosition(npc);

        if (consecutiveStalls >= MAX_CONSECUTIVE_STALLS) {
            consecutiveStalls = 0;
            npc.getNavigation().stop();
            npc.teleportTo(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
        }
    }

    private void recordPosition(Mob npc) {
        lastX = npc.getX();
        lastY = npc.getY();
        lastZ = npc.getZ();
    }
}
