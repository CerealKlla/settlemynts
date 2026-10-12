package com.github.cerealklla.settlemynts.farm;

import java.util.Map;
import java.util.UUID;

import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.plotsign.PlotConfigSignBlockEntity;
import com.github.cerealklla.settlemynts.plotsign.PlotConfigSignIndex;
import com.github.cerealklla.settlemynts.registration.ModEntities;
import com.github.cerealklla.settlemynts.zone.PlotRecord;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Fills a Farm plot's worker slot -- exact mirror of {@code lumberyard.LumberjackSpawnTicker}, see
 * that class's own doc for the shared trigger/respawn shape.
 */
public final class FarmerSpawnTicker {

    private static final Identifier FARM_ZONE_TYPE_ID = Identifier.fromNamespaceAndPath("blueprynts", "farm");
    private static final int SCAN_INTERVAL_TICKS = 100;
    // Staggered 2026-10-12 -- real report: every mob near the player stutter-moving in lockstep,
    // same symptom as an earlier guard-lag investigation. Root cause found this time: eight
    // independent full-world-scanning tickers across three mods (this one, GuardSpawnTicker,
    // LumberjackSpawnTicker, MineshaftSpawnTicker, ResidentSpawnTicker, VillagerRespawnTicker,
    // NaturalVillageShopLinkTicker, Myssions' QuestDeadlineTicker) all independently picked the same
    // "every 100 ticks" cadence with no offset, so every one of their scans landed on the exact same
    // tick (0, 100, 200, ...) and their costs compounded into one periodic ~50-180ms spike every 5
    // seconds -- confirmed live via the already-present [LagDiagnostics] slow-tick logger, which was
    // firing almost exactly every 5.00s. Each ticker keeps its own 100-tick (5s) cadence unchanged;
    // only *which* tick within that window it fires on changes, spreading the same total work out
    // instead of piling it onto one tick.
    private static final int TICK_OFFSET = 0;

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if ((server.getTickCount() + TICK_OFFSET) % SCAN_INTERVAL_TICKS != 0) {
            return;
        }
        for (Map.Entry<UUID, GlobalPos> entry : PlotConfigSignIndex.get(server).all().entrySet()) {
            GlobalPos pos = entry.getValue();
            ServerLevel level = server.getLevel(pos.dimension());
            if (level == null) {
                continue;
            }
            fillWorker(level, entry.getKey(), pos.pos());
        }
    }

    private void fillWorker(ServerLevel level, UUID plotId, BlockPos signPos) {
        if (!(level.getBlockEntity(signPos) instanceof PlotConfigSignBlockEntity sign)
                || sign.settlementCoreId() == null
                || !(level.getEntity(sign.settlementCoreId()) instanceof GhostTownHallCoreEntity core)) {
            return;
        }
        PlotRecord plot = core.getPlots().stream().filter(p -> p.plotId().equals(plotId)).findFirst().orElse(null);
        if (plot == null || plot.owner().isPresent() || !plot.zoneTypeId().equals(FARM_ZONE_TYPE_ID)) {
            return;
        }

        UUID existing = sign.farmerWorkerId();
        if (existing != null) {
            if (level.getEntity(existing) instanceof FarmerWorkerEntity living && living.isAlive()) {
                // Idempotent backfill (2026-10-09) -- picks up the profession fix below for a
                // worker that was already alive, spawned before this feature existed. Cheap to set
                // unconditionally every scan (just replaces the record with an identical one once
                // already correct).
                living.setVillagerData(living.getVillagerData().withProfession(level.registryAccess(), net.minecraft.world.entity.npc.villager.VillagerProfession.FARMER));
                return;
            }
            sign.setFarmerWorkerId(null);
        }

        FarmerWorkerEntity worker = new FarmerWorkerEntity(ModEntities.FARMER_WORKER.get(), level);
        worker.setPos(signPos.getX() + 0.5, signPos.getY(), signPos.getZ() + 0.5);
        worker.setPlotIdentity(sign.settlementCoreId(), plotId);
        // Cosmetic only (2026-10-09, explicit request) -- the zone here is always Farm, so the
        // profession is just the matching vanilla robe, not a job-site-claim (this worker's Brain is
        // never ticked -- same reasoning as resident.ResidentVillagerEntity's own class doc).
        worker.setVillagerData(worker.getVillagerData().withProfession(level.registryAccess(), net.minecraft.world.entity.npc.villager.VillagerProfession.FARMER));
        level.addFreshEntity(worker);
        sign.setFarmerWorkerId(worker.getUUID());
    }
}
