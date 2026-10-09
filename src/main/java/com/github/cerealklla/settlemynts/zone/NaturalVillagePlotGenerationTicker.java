package com.github.cerealklla.settlemynts.zone;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Periodically retries every village in {@link NaturalVillagePlotPending}'s queue, generating
 * whichever pieces have become ready since the last pass (see that class's own doc for why
 * generation is deferred at all). Same periodic-scan shape as {@code resident.ResidentSpawnTicker}.
 */
public final class NaturalVillagePlotGenerationTicker {

    private static final int SCAN_INTERVAL_TICKS = 60; // 3 seconds.
    private static final int RECHECK_INTERVAL_TICKS = 200; // 10 seconds -- see NaturalVillageZoneRecheckPending's own doc.

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % SCAN_INTERVAL_TICKS == 0) {
            // Copy first -- NaturalVillagePlotGenerator.processPending removes finished entries from
            // the same backing map as it goes.
            List<NaturalVillagePlotPending.Entry> entries = new ArrayList<>(NaturalVillagePlotPending.entries());
            for (NaturalVillagePlotPending.Entry entry : entries) {
                NaturalVillagePlotGenerator.processPending(entry);
            }
        }
        if (server.getTickCount() % RECHECK_INTERVAL_TICKS == 0) {
            List<NaturalVillageZoneRecheckPending.Entry> rechecks = new ArrayList<>(NaturalVillageZoneRecheckPending.entries());
            for (NaturalVillageZoneRecheckPending.Entry entry : rechecks) {
                NaturalVillagePlotGenerator.processZoneRecheck(entry);
            }
        }
    }
}
