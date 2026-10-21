package com.github.cerealklla.settlemynts.diagnostics;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

/**
 * Temporary instrumentation (2026-10-21, real incident: "I was only afk in town for 20 minutes and
 * came back to a fully locked up computer" -- on top of the already-known, already-flagged sustained
 * baseline tick lag, see {@link LagDiagnostics}'s own doc). {@link LagDiagnostics} already catches
 * *that a tick was slow*, but has no breakdown of *why* -- a handful of existing single-point
 * timers (this class's own doc lists them) only ever catch one unusually slow individual call, which
 * misses the far more likely real shape of this problem: hundreds of individually-cheap per-NPC Goal
 * ticks adding up across a large live population, no single one of which would ever cross its own
 * threshold alone.
 *
 * <p>This is a plain per-tick accumulator, not a persistent metric -- every custom {@code Goal}'s
 * {@code tick()}/{@code canUse()} this suite owns (the vanilla-provided melee/bow/ranged goals are
 * left alone, not suspect) wraps its body and calls {@link #record} with its own class name as the
 * category. {@link LagDiagnostics#onTickPost} calls {@link #reportIfSlow} once per tick, which logs
 * a sorted breakdown (worst category first) only when the tick as a whole was already slow, then
 * clears everything either way -- this never logs on a normal tick, and the accumulation itself is
 * cheap (a few map operations), not a real tick-time contributor of its own. Server thread only, no
 * synchronization needed -- every Goal tick and this class's own read happen on the same thread.
 *
 * <p>Remove once the real culprit is confirmed and fixed -- not a permanent feature, same as every
 * other diagnostic in this package.
 */
public final class TickProfiler {

    private static final Map<String, Long> nanosByCategory = new LinkedHashMap<>();
    private static final Map<String, Integer> callsByCategory = new LinkedHashMap<>();

    private TickProfiler() {
    }

    public static void record(String category, long elapsedNanos) {
        nanosByCategory.merge(category, elapsedNanos, Long::sum);
        callsByCategory.merge(category, 1, Integer::sum);
    }

    /** Times {@code work} and records it under {@code category} in one call -- for a {@code void} Goal method. */
    public static void time(String category, Runnable work) {
        long start = System.nanoTime();
        try {
            work.run();
        } finally {
            record(category, System.nanoTime() - start);
        }
    }

    /** Same as {@link #time}, for a Goal method that returns a value (e.g. {@code canUse()}). */
    public static <T> T timeValue(String category, java.util.function.Supplier<T> work) {
        long start = System.nanoTime();
        try {
            return work.get();
        } finally {
            record(category, System.nanoTime() - start);
        }
    }

    /** Called once per tick by {@link LagDiagnostics}. Logs a breakdown only if the tick was slow; always clears for the next tick. */
    static void reportIfSlow(long tickElapsedMs) {
        if (!nanosByCategory.isEmpty() && tickElapsedMs >= LagDiagnostics.THRESHOLD_MS) {
            StringBuilder sb = new StringBuilder();
            nanosByCategory.entrySet().stream()
                    .sorted(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue).reversed())
                    .forEach(entry -> {
                        String category = entry.getKey();
                        long ms = entry.getValue() / 1_000_000L;
                        int calls = callsByCategory.getOrDefault(category, 0);
                        sb.append(category).append('=').append(ms).append("ms(x").append(calls).append(") ");
                    });
            SettlemyntsMod.LOGGER.info("[LagDiagnostics] slow tick breakdown: {}", sb.toString().trim());
        }
        nanosByCategory.clear();
        callsByCategory.clear();
    }
}
