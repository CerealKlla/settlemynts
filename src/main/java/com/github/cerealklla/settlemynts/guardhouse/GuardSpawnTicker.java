package com.github.cerealklla.settlemynts.guardhouse;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.github.cerealklla.kyt.loadout.LoadoutRecord;
import com.github.cerealklla.settlemynts.bridge.KytGuardhouseBridge;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.registration.ModEntities;
import com.github.cerealklla.settlemynts.zone.PlotRecord;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Fills any Guardhouse garrison slot that has a Kyt bound but no living guard (design doc Section
 * 14a, guard-spawning pass) -- shape mirrors {@code construction.NpcAutoFundingTicker} closely: a
 * cheap periodic scan, no per-slot persisted "last spawned" state needed.
 *
 * <p><b>Trigger model, and its one real side effect</b>: this is an "is this slot filled with a
 * living guard?" scan, not a one-shot spawn-on-bind. That means a dead guard's slot is naturally
 * refilled on the next scan -- a genuine, intentional side effect of this trigger choice, not a
 * separate "respawn" feature (see the guard-spawning plan's own scope notes).
 *
 * <p><b>Known scaling simplification</b>, same as every other full-world-scanning ticker in this
 * mod: a Guardhouse whose chunk isn't currently loaded is silently skipped for that scan, same
 * limitation {@code NpcAutoFundingTicker}'s own box-delivery pacing already has.
 */
public final class GuardSpawnTicker {

    // Cheap enough at 5 seconds to keep garrison slots filled promptly without scanning every tick.
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
        for (Map.Entry<UUID, GlobalPos> entry : GuardhouseIndex.get(server).all().entrySet()) {
            GlobalPos pos = entry.getValue();
            ServerLevel level = server.getLevel(pos.dimension());
            if (level == null) {
                continue;
            }
            fillGarrison(level, entry.getKey(), pos.pos());
        }
    }

    private void fillGarrison(ServerLevel level, UUID plotId, BlockPos guardhousePos) {
        if (!(level.getBlockEntity(guardhousePos) instanceof GuardhouseBlockEntity guardhouse)
                || guardhouse.settlementCoreId() == null
                || !(level.getEntity(guardhouse.settlementCoreId()) instanceof GhostTownHallCoreEntity core)) {
            return; // Chunk not loaded, or the Guardhouse/core couldn't be resolved -- skip this scan.
        }
        PlotRecord plot = core.getPlots().stream().filter(p -> p.plotId().equals(plotId)).findFirst().orElse(null);
        if (plot == null) {
            return;
        }
        int tier = plot.boxPos().isPresent() && ModList.get().isLoaded("blueprynts")
                ? com.github.cerealklla.blueprynts.api.Blueprynts.getConstructionBoxTier(level, plot.boxPos().get()).orElse(0)
                : 0;
        int capacity = GuardhouseConstants.capacityForTier(tier);
        for (int i = 0; i < capacity; i++) {
            fillSlot(level, guardhouse, guardhousePos, i, plotId);
        }
    }

    private void fillSlot(ServerLevel level, GuardhouseBlockEntity guardhouse, BlockPos guardhousePos, int index, UUID plotId) {
        GarrisonSlot slot = guardhouse.garrisonSlots().get(index);
        if (slot.kytLoadoutName().isEmpty()) {
            return;
        }
        // Trusts the persisted slot outright now -- fixed 2026-10-13, real live bug: this used to
        // also require resolving the guard's live entity (level.getEntity + isAlive) before counting
        // the slot as filled, but a patrolling guard easily wanders outside currently-loaded chunks
        // (it can patrol the whole settlement's road network, not just stand next to the Guardhouse),
        // and an entity can only ever actually die while its own chunk is loaded and ticking -- so
        // "can't resolve it right now" was never real evidence of death, just evidence it was out of
        // render range. That false "it died" conclusion spawned a redundant guard into the same slot
        // every time, compounding across restarts (confirmed live: 9 guards found tagged to one plot,
        // whose absolute max garrison size is 8). See GuardEntity#die/GuardhouseBlockEntity#clearGuardIdIfMatches
        // for the real, push-based replacement -- a slot is only ever cleared by an actual death now.
        if (guardhouse.guardId(index).isPresent()) {
            return;
        }
        Optional<LoadoutRecord> record = KytGuardhouseBridge.getKyt(slot.kytLoadoutName().get());
        if (record.isEmpty()) {
            return; // Stale/deleted Kyt name -- treated the same as unset, per GarrisonSlot's own doc.
        }
        GuardEntity guard = new GuardEntity(ModEntities.GUARD.get(), level);
        BlockPos spawnPos = guardhousePos.above();
        guard.setPos(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
        guard.equipFromLoadout(record.get());
        guard.setPlotIdentity(guardhouse.settlementCoreId(), plotId);
        level.addFreshEntity(guard);
        guardhouse.setGuardId(index, guard.getUUID());
    }
}
