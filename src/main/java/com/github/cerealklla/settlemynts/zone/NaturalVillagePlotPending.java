package com.github.cerealklla.settlemynts.zone;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.github.cerealklla.cartographyr.geo.EntityId;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/**
 * In-memory (not persisted -- see this class's own limitation note below) queue of natural villages
 * whose plots haven't finished generating yet. Added 2026-10-08, fixing a real bug found during live
 * testing: generating every piece's plot immediately when {@code NaturalVillagePlotListener} first
 * sees the village (the moment its *origin* chunk loads) ran long before most of a sprawling
 * village's other chunks had actually generated their blocks/entities -- every piece silently read as
 * "no villager, no farmland" and fell back to Private Residence, which is exactly what the user's
 * live test showed ("every single plot was a Residence despite there being at least 2 farm-style
 * sections"). {@link NaturalVillagePlotGenerationTicker} now retries each not-yet-ready piece until
 * its own chunk(s) are actually loaded before reading it.
 *
 * <p><b>Known limitation</b>: this queue is lost on server restart. A village whose generation was
 * still in progress when the server stopped will restart generation from scratch next time its
 * origin chunk loads -- since per-piece completion is only tracked in this in-memory array, not
 * persisted, already-generated pieces from before the restart would be regenerated too (duplicate
 * plots for those pieces). Accepted for now as a rare edge case in an already-experimental feature,
 * not worth a persisted index for.
 */
public final class NaturalVillagePlotPending {

    record Entry(ServerLevel level, StructureStart start, SettlementKey settlement, boolean[] pieceDone) {
    }

    private static final Map<SettlementKey, Entry> PENDING = new ConcurrentHashMap<>();

    private NaturalVillagePlotPending() {
    }

    static void enqueueIfAbsent(ServerLevel level, StructureStart start, EntityId settlementEntityId) {
        SettlementKey key = SettlementKey.of(level, settlementEntityId.value());
        PENDING.computeIfAbsent(key, k -> new Entry(level, start, key, new boolean[start.getPieces().size()]));
    }

    static Collection<Entry> entries() {
        return PENDING.values();
    }

    static void remove(SettlementKey settlement) {
        PENDING.remove(settlement);
    }

    /**
     * Called on {@code ServerStartingEvent} (see {@code SettlemyntsMod}) -- a real bug found in live
     * testing, 2026-10-08: this queue is keyed by Cartographyr's raw per-world {@code EntityId}
     * value, which restarts from a small number in every fresh world. Loading a second world within
     * the same client/JVM session (dev-testing routinely does this) could silently collide with a
     * still-pending entry left over from the *previous*, now-dead world -- {@code
     * computeIfAbsent} would see the id already "in use" and never queue the new world's village at
     * all. Clearing on every server start closes that gap; losing an in-progress entry across an
     * actual mid-generation restart is the same already-accepted limitation this class's own class
     * doc describes.
     */
    static void clear() {
        PENDING.clear();
    }

    /** Entry point for {@code SettlemyntsMod}'s {@code ServerStartingEvent} listener -- clears both natural-village pending queues at once. */
    public static void clearStaleQueuesOnServerStart() {
        clear();
        NaturalVillageZoneRecheckPending.clear();
    }
}
