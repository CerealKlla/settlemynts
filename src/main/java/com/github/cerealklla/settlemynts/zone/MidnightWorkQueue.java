package com.github.cerealklla.settlemynts.zone;

import java.util.ArrayDeque;
import java.util.Deque;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

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

    public void enqueue(Runnable job) {
        jobs.add(job);
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
