package com.github.cerealklla.settlemynts.zone;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Queue of generated natural-village plots still waiting on the *right* villager to tag as their
 * Shop-interaction point. Two modes, both added 2026-10-08:
 *
 * <ul>
 *   <li><b>Job-site claim</b> ({@link #enqueueJobSiteClaim}) -- the plot's zone was already correctly
 *   determined from a real job-site block (Composter, Blast Furnace, etc.), but nobody had claimed it
 *   as their workstation yet at generation time. Real live-test correction, same day: tagging
 *   "whichever villager happened to be standing nearby" at generation time is a different villager
 *   than the one who actually works that job site, so a shop's "farmer" could easily be a random
 *   passerby instead. This mode waits for vanilla's own job-site-claiming AI (via {@code
 *   MemoryModuleType.JOB_SITE}, a real {@code GlobalPos} on the claiming villager's own {@code Brain})
 *   to point at this exact block, then tags *that* villager.</li>
 *   <li><b>Profession watch</b> ({@link #enqueueProfessionWatch}) -- the original mechanism: a piece
 *   with no real job-site block (its zone came from the farmland heuristic or the Residence fallback)
 *   had a villager nearby with no profession yet; re-checks whether it's since claimed one this mod
 *   maps to a different, better Zone Type.</li>
 * </ul>
 *
 * <p>In-memory only, like {@link NaturalVillagePlotPending} -- an interrupted recheck (server
 * restart mid-window) just means that plot stays at its original guess, not worth persisting for.
 */
final class NaturalVillageZoneRecheckPending {

    static final int MAX_ATTEMPTS = 20; // ~20 * RECHECK_INTERVAL_TICKS (see the ticker) -- a few minutes.

    static final class Entry {
        final ServerLevel level;
        final UUID plotId;
        final SettlementKey settlement;
        /** Only set in profession-watch mode -- the villager originally tagged, re-checked for a newly-claimed profession. */
        final int villagerEntityId;
        /** Only set in job-site-claim mode -- the real job-site block this plot's eventual worker should be claiming. */
        final BlockPos jobSitePos;
        int attemptsLeft;

        private Entry(ServerLevel level, UUID plotId, SettlementKey settlement, int villagerEntityId, BlockPos jobSitePos) {
            this.level = level;
            this.plotId = plotId;
            this.settlement = settlement;
            this.villagerEntityId = villagerEntityId;
            this.jobSitePos = jobSitePos;
            this.attemptsLeft = MAX_ATTEMPTS;
        }
    }

    private static final Map<UUID, Entry> PENDING = new ConcurrentHashMap<>();

    private NaturalVillageZoneRecheckPending() {
    }

    static void enqueueProfessionWatch(ServerLevel level, UUID plotId, SettlementKey settlement, int villagerEntityId) {
        PENDING.put(plotId, new Entry(level, plotId, settlement, villagerEntityId, null));
    }

    static void enqueueJobSiteClaim(ServerLevel level, UUID plotId, SettlementKey settlement, BlockPos jobSitePos) {
        PENDING.put(plotId, new Entry(level, plotId, settlement, -1, jobSitePos));
    }

    static Collection<Entry> entries() {
        return PENDING.values();
    }

    static void remove(UUID plotId) {
        PENDING.remove(plotId);
    }

    /** Called on {@code ServerStartingEvent} -- see {@link NaturalVillagePlotPending#clear()}'s own doc for why (same stale-world-id-collision bug, same fix). */
    static void clear() {
        PENDING.clear();
    }
}
