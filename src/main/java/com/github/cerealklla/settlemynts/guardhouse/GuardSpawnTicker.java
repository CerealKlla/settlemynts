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
        if (stand.boundKytName().isEmpty() || stand.assignedGuardId().isPresent()) {
            return;
        }
        if (level.getGameTime() < stand.respawnReadyAtGameTime()) {
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

        GuardEntity guard = new GuardEntity(ModEntities.GUARD.get(), level);
        BlockPos spawnPos = stand.blockPosition();
        guard.setPos(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
        List<net.minecraft.world.Container> plotBoxes = Settlemynts.resolvePlotBoxes(level, plot.plotId());
        guard.equipFromLoadoutUsingBoxes(record.get(), plotBoxes);
        guard.setPlotIdentity(stand.settlementCoreId(), stand.plotId());
        guard.setAssignedStandId(standId);
        guard.setHomePos(spawnPos);
        level.addFreshEntity(guard);
        stand.setAssignedGuardId(guard.getUUID());
    }
}
