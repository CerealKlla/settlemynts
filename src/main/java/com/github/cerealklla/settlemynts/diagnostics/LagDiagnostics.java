package com.github.cerealklla.settlemynts.diagnostics;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Temporary instrumentation (2026-10-10, real report: "when the server is running smooth the guards
 * walk smoothly, when there is lag the guards move in increasingly delayed bursts of movement" --
 * confirmed real via live {@code /tick query} showing a consistent ~125-130ms P99 even with a healthy
 * average, and the vanilla tick profiler's {@code debug start}/{@code debug stop} not writing a
 * readable report in this environment). Logs any single server tick that takes longer than {@link
 * #THRESHOLD_NANOS}, so a slow-tick log line can be correlated against whatever else logs around the
 * same timestamp (a settlement trading pass, a guard's road-route pick, etc.) to find the real cause
 * instead of guessing. Remove once the actual culprit is found and fixed -- not a permanent feature.
 */
public final class LagDiagnostics {

    private static final long THRESHOLD_NANOS = 40_000_000L; // 40ms -- vanilla's own tick budget is 50ms.
    private long tickStartNanos;

    @SubscribeEvent
    public void onTickPre(ServerTickEvent.Pre event) {
        tickStartNanos = System.nanoTime();
    }

    @SubscribeEvent
    public void onTickPost(ServerTickEvent.Post event) {
        long elapsedMs = (System.nanoTime() - tickStartNanos) / 1_000_000L;
        if (elapsedMs >= THRESHOLD_NANOS / 1_000_000L) {
            SettlemyntsMod.LOGGER.info("[LagDiagnostics] slow tick: {}ms", elapsedMs);
        }
    }
}
