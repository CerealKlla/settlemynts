package com.github.cerealklla.settlemynts.zone;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.server.level.ServerLevel;

/**
 * Spreads the nightly midnight settlement pass's actual work across many server ticks instead of
 * running it all synchronously within the single tick the day boundary is detected on -- real
 * production incident, 2026-10-20: a single settlement's {@link PlannedInventoryClearing#settleGroup}
 * pass (10 participants, 105 resources, recursive buy-vs-craft cost resolution per resource) took 57
 * real seconds end to end in one tick, well past the dedicated server's 60-second {@code
 * ServerWatchdog} threshold -- the watchdog considered the server crashed and force-killed the whole
 * Production process mid-tick ("A single server tick took 60.01 seconds... forcibly shutdown").
 *
 * <p>There is no safe way to run this work on a genuinely separate thread instead -- every step here
 * reads/writes live {@code Container} contents and {@code PlotRecord} state, which is only ever safe
 * from the main server thread in vanilla/NeoForge. The real fix (the same one vanilla and most mods
 * use for "this task is too big for one tick," e.g. chunk generation/saving) is to chop the work into
 * small jobs and drain a time-bounded batch of them every tick until the queue empties, so no single
 * tick's share of the work can ever itself approach a tick's own budget, regardless of how large a
 * settlement's participant/resource count grows.
 */
public final class MidnightWorkQueue {

    // Generous relative to a 50ms tick target -- leaves the rest of that tick's budget (world
    // ticking, every other mod, players) essentially untouched while still draining a real backlog
    // in a reasonable number of ticks rather than trickling it out over many real minutes.
    private static final long TICK_BUDGET_NANOS = 10_000_000L; // 10ms

    private final Deque<Runnable> jobs = new ArrayDeque<>();

    // Real requirement, 2026-10-22 (see decisions.md "Midnight settlement processing must force-load
    // chunks"): a settlement's own midnight pipeline is many small jobs (one per resource, per plot
    // target, per guardhouse pair, etc. -- scattered across PlannedInventoryClearing/
    // GuardhouseFoodAutoBuy/DefenseSpendingTransfer), not one atomic unit, and those jobs interleave
    // with every other settlement's own jobs in this same shared FIFO queue. A plain "enqueue one
    // final release job" wouldn't work -- several of these jobs re-enqueue themselves as
    // continuations (attemptOneCraftableUnit, attemptOneOwnStockCraft), which could land AFTER a
    // naively-placed release. This is a reference count instead: every job enqueued for a settlement
    // increments it, every job that finishes running decrements it, and whichever job happens to take
    // it to zero (there's always exactly one, since jobs run strictly one at a time) triggers the
    // real release right then.
    //
    // <p>{@code currentLevel}/{@code currentSettlementId} are the ambient context: whichever
    // settlement-scoped job is actually executing right now sets these for the duration of its own
    // {@code run()}. This means a PLAIN {@link #enqueue} call made synchronously from inside that
    // job's own body -- or from a later continuation job re-enqueuing itself the same way -- is
    // automatically folded into the same settlement's ref count, with zero changes needed at any of
    // those call sites (PlannedInventoryClearing/GuardhouseFoodAutoBuy/DefenseSpendingTransfer all
    // just keep calling {@code queue.enqueue(...)} exactly as before).
    private final Map<UUID, Integer> settlementRefCounts = new HashMap<>();
    private ServerLevel currentLevel;
    private UUID currentSettlementId;

    public void enqueue(Runnable job) {
        if (currentSettlementId != null) {
            enqueueForSettlement(currentLevel, currentSettlementId, job);
            return;
        }
        jobs.add(job);
    }

    /** Only the top-level call (one per settlement, from {@code ShopMidnightRestockTicker}) needs to
     * call this explicitly -- see this class's own doc for how everything nested under it inherits
     * the same settlement automatically via {@link #enqueue}. */
    public void enqueueForSettlement(ServerLevel level, UUID settlementId, Runnable job) {
        settlementRefCounts.merge(settlementId, 1, Integer::sum);
        jobs.add(() -> {
            ServerLevel previousLevel = currentLevel;
            UUID previousSettlementId = currentSettlementId;
            currentLevel = level;
            currentSettlementId = settlementId;
            try {
                job.run();
            } finally {
                currentLevel = previousLevel;
                currentSettlementId = previousSettlementId;
                int remaining = settlementRefCounts.merge(settlementId, -1, Integer::sum);
                if (remaining <= 0) {
                    settlementRefCounts.remove(settlementId);
                    MidnightChunkWaker.sleep(level, settlementId);
                }
            }
        });
    }

    public boolean isEmpty() {
        return jobs.isEmpty();
    }

    /** Drains queued jobs until either the queue is empty or this tick's time budget runs out. */
    public void pump() {
        if (jobs.isEmpty()) {
            return;
        }
        long deadline = System.nanoTime() + TICK_BUDGET_NANOS;
        while (!jobs.isEmpty() && System.nanoTime() < deadline) {
            Runnable job = jobs.poll();
            // Temporary diagnostic, 2026-10-20 (real incident: the midnight pass logged 8+ real
            // seconds wall-clock and individual ticks over 2 seconds despite this 10ms-per-tick
            // budget, meaning some single enqueued job is itself far larger than "small" -- pump()
            // has no way to preempt mid-job, so one oversized job blows the whole tick's budget by
            // itself. Logs which job class (and how long) whenever a single job alone is this slow,
            // to find the still-unchunked spot instead of guessing. Remove once found and fixed.
            long jobStart = System.nanoTime();
            try {
                job.run();
            } catch (RuntimeException e) {
                SettlemyntsMod.LOGGER.error("MidnightWorkQueue: a queued job threw", e);
            } finally {
                long jobElapsedNanos = System.nanoTime() - jobStart;
                long jobElapsedMs = jobElapsedNanos / 1_000_000L;
                if (jobElapsedMs >= 20) {
                    SettlemyntsMod.LOGGER.info("[LagDiagnostics] MidnightWorkQueue job took {}ms ({})", jobElapsedMs, job.getClass().getName());
                }
                // Feeds diagnostics.TickProfiler too (2026-10-21), one category per job class, so
                // this pass's real share of a slow tick shows up in the same aggregate breakdown as
                // every other suspect system.
                com.github.cerealklla.settlemynts.diagnostics.TickProfiler.record(
                        "MidnightWorkQueue:" + job.getClass().getSimpleName(), jobElapsedNanos);
            }
        }
    }
}
