package com.github.cerealklla.settlemynts.guardhouse;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.github.cerealklla.kyt.loadout.LoadoutRecord;
import com.github.cerealklla.settlemynts.api.Settlemynts;
import com.github.cerealklla.settlemynts.bridge.KytGuardhouseBridge;
import com.github.cerealklla.settlemynts.registration.ModEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Fills any placed {@link GuardKitStandEntity} that has a Kyt bound but no living guard -- Guard
 * Kit Stand overhaul, 2026-10-16, rewritten from the old "iterate a Guardhouse's 8 garrison slots"
 * shape to "iterate every known stand via {@link GuardKitStandIndex}" (each stand is independently
 * placed/picked-up, not a fixed-size array owned by one Guardhouse block anymore). Same cheap
 * periodic-scan shape as {@code construction.NpcAutoFundingTicker} otherwise -- no per-stand
 * persisted "last spawned" state needed beyond the stand's own {@code respawnReadyAtGameTime}.
 *
 * <p>Spawns only if: a Kyt is bound, no {@code assignedGuardId} is currently alive/tracked, and
 * {@code level.getGameTime() >= respawnReadyAtGameTime} (set by {@link GuardEntity#die} via {@link
 * GuardRespawnFunding} -- 30 seconds if food was funded, 5 minutes otherwise). Equips the spawned
 * guard from the plot's own boxes (best-effort per slot, see {@link
 * GuardEntity#equipFromLoadoutUsingBoxes}) instead of the old unconditional full-Kyt equip.
 *
 * <p><b>Topping up an already-alive guard's missing gear moved off this ticker entirely,
 * 2026-10-22</b> -- explicit user correction: "Guard equip rescan should not be a tick, There is an
 * event for gear being added to the box, use that." The 2026-10-17 version of this feature polled
 * every alive guard here on this same 5-second cadence; it's now handled by {@link
 * GuardEquipBoxChangeListener}, reacting to {@code PlayerContainerEvent.Close} instead (the same
 * "player just finished interacting with a container" substitute this suite already uses elsewhere
 * for problems that would otherwise need polling -- see that class's own doc). This ticker's only
 * remaining job is spawning a replacement guard once a dead stand's respawn timer is ready, which
 * is genuinely time-gated and has no event to react to instead.
 *
 * <p><b>Known scaling simplification</b>, same as every other full-world-scanning ticker in this
 * mod: a stand whose chunk isn't currently loaded is silently skipped for that scan, same
 * limitation {@code NpcAutoFundingTicker}'s own box-delivery pacing already has.
 */
public final class GuardSpawnTicker {

    // Cheap enough at 5 seconds to keep stands filled promptly without scanning every tick.
    private static final int SCAN_INTERVAL_TICKS = 100;
    // Staggered 2026-10-12 against the other seven 100-tick full-world scan tickers across this
    // suite -- see FarmerSpawnTicker's own doc for the full "thundering herd" root cause.
    private static final int TICK_OFFSET = 12;

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (!KytGuardhouseBridge.isLoaded()) {
            return; // No guards ever spawn on a Kyt-less server.
        }
        MinecraftServer server = event.getServer();
        if ((server.getTickCount() + TICK_OFFSET) % SCAN_INTERVAL_TICKS != 0) {
            return;
        }
        for (Map.Entry<UUID, GlobalPos> entry : GuardKitStandIndex.get(server).all().entrySet()) {
            GlobalPos pos = entry.getValue();
            ServerLevel level = server.getLevel(pos.dimension());
            if (level == null) {
                continue;
            }
            fillStand(level, entry.getKey());
        }
    }

    private void fillStand(ServerLevel level, UUID standId) {
        if (!(level.getEntity(standId) instanceof GuardKitStandEntity stand)
                || stand.settlementCoreId() == null
                || !(level.getEntity(stand.settlementCoreId()) instanceof com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity core)) {
            return; // Chunk not loaded, or the stand/core couldn't be resolved -- skip this scan.
        }
        // Real bug fix, 2026-10-29 -- see GuardPendingDeathIndex's own doc for the full incident.
        // Applied as the very first thing once this stand is actually reachable, so by the time the
        // assignedGuardId() check below runs, a real death (however long ago, in whatever
        // dimension) has already been correctly reflected -- restoring "present means alive" as a
        // trustworthy invariant instead of trying to re-derive it via a cross-dimension entity
        // lookup, which an earlier version of this fix attempted and which risked duplicating a
        // guard that's genuinely alive but simply standing in a different dimension right now (see
        // that class's own doc for why).
        GuardPendingDeathIndex.get(level.getServer()).take(standId).ifPresent(respawnReadyAtGameTime -> {
            stand.clearAssignedGuardId();
            stand.setRespawnReadyAtGameTime(respawnReadyAtGameTime);
        });
        if (stand.boundKytName().isEmpty()) {
            return;
        }
        Optional<LoadoutRecord> record = KytGuardhouseBridge.getKyt(stand.boundKytName().get());
        if (record.isEmpty()) {
            return; // Stale/deleted Kyt name -- treated the same as unset, per GuardKitStandEntity's own doc.
        }
        com.github.cerealklla.settlemynts.zone.PlotRecord plot = core.getPlots().stream()
                .filter(p -> p.plotId().equals(stand.plotId())).findFirst().orElse(null);
        if (plot == null) {
            return;
        }

        if (stand.assignedGuardId().isPresent()) {
            // Guard already alive (GuardEntity#die reliably clears this via GuardPendingDeathIndex
            // now, applied above, so "present" is trustworthy again) -- gear top-up is event-driven
            // now (GuardEquipBoxChangeListener). A companion's cooldown elapsing has no real event
            // to react to at all (pure wall-clock time passing) -- this 5-second scan, already
            // visiting every stand for the guard's own death/respawn timer, is the closest thing to
            // a "timer" available without a dedicated new scheduling mechanism; see
            // GuardEntity#recheckCompanions's own doc.
            if (level.getEntity(stand.assignedGuardId().get()) instanceof GuardEntity guard) {
                guard.recheckCompanions(level);
            }
            return;
        }
        if (level.getGameTime() < stand.respawnReadyAtGameTime()) {
            return;
        }

        GuardEntity guard = new GuardEntity(ModEntities.GUARD.get(), level);
        BlockPos spawnPos = stand.blockPosition();
        guard.setPos(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
        List<net.minecraft.world.Container> plotBoxes = resolvePlotBoxes(level, plot);
        guard.equipFromLoadoutUsingBoxes(record.get(), plotBoxes);
        guard.setPlotIdentity(stand.settlementCoreId(), stand.plotId());
        guard.setAssignedStandId(standId);
        guard.setHomePos(spawnPos);
        level.addFreshEntity(guard);
        stand.setAssignedGuardId(guard.getUUID());
    }

    /**
     * Real live incident, 2026-10-22: a JFR profile of Production showed 49% of all sampled CPU time
     * on the server thread inside {@code fastutil}'s {@code LongAVLTreeSet}, reached from here (via
     * {@code Settlemynts#resolvePlotBoxes(ServerLevel, UUID)}'s own full-world {@code
     * GhostTownHallCoreEntity} scan) every single 5-second scan, for every Guard Kit Stand -- the
     * exact "resolve once, reuse" gap {@code Settlemynts#resolvePolygonDirect}/{@code
     * #resolveBoxesForPolygon} were already built to close for other callers (see those methods' own
     * docs -- {@code mineshaft.MinerWorkGoal} had the identical bug fixed 2026-10-21) just never
     * applied here. This method already has the real {@code PlotRecord} in hand (resolved a few lines
     * above, from {@code core.getPlots()}) at both call sites, so there was never a reason to
     * re-derive it from scratch via a plot-id lookup.
     */
    private static List<net.minecraft.world.Container> resolvePlotBoxes(ServerLevel level, com.github.cerealklla.settlemynts.zone.PlotRecord plot) {
        return Settlemynts.resolvePolygonDirect(level, plot)
                .map(polygon -> Settlemynts.resolveBoxesForPolygon(level, polygon))
                .orElse(List.of());
    }
}
