package com.github.cerealklla.settlemynts.resident;

import java.util.Map;
import java.util.UUID;

import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.plotsign.PlotConfigSignBlockEntity;
import com.github.cerealklla.settlemynts.plotsign.PlotConfigSignIndex;
import com.github.cerealklla.settlemynts.registration.ModEntities;
import com.github.cerealklla.settlemynts.zone.PlotRecord;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Fills any NPC-owned plot's Plot Config Sign with a living {@link ResidentVillagerEntity} (design
 * doc Section 14a, added 2026-10-05, explicit user request). Shape mirrors {@code
 * guardhouse.GuardSpawnTicker} exactly: a cheap periodic "is this plot's slot filled with a living
 * resident right now?" scan over every known sign ({@link PlotConfigSignIndex}, not a world-wide
 * entity scan), no per-plot persisted "last spawned"/death-timer state needed.
 *
 * <p><b>Trigger model, and its one real side effect</b> (same as {@code GuardSpawnTicker}'s own
 * doc): this is a presence check, not a one-shot spawn-on-Finalize. A killed resident's slot is
 * naturally empty on the next scan (~5s later, {@link #SCAN_INTERVAL_TICKS}) and gets refilled for
 * free -- this single mechanism **is** the "respawns 5 seconds after death" requirement, no
 * separate timer needed.
 *
 * <p>Every finalized plot with {@code PlotRecord#owner().isEmpty()} (NPC-owned, the default -- see
 * {@code construction.NpcAutoFundingTicker}'s identical check), or a player-owned plot with {@code
 * PlotRecord#npcHired()} set (added 2026-10-15, see that field's own doc and {@code
 * zone.NpcMaintenanceTicker}), is eligible -- but only once {@code zone.PlotWorkerKind#resolve}
 * (added 2026-10-16, replacing four separate hand-duplicated zone-type checks -- see its own doc)
 * says this plot's real worker kind is {@code RESIDENT}: never a Guardhouse or Town Hall (neither
 * has a worker-NPC concept at all), and never a plot whose real Zone Type -- or an explicit {@code
 * zone.PlotJob} "Change Job" override -- resolves to Farmer/Lumberjack/Miner instead (see {@code
 * lumberyard.LumberjackSpawnTicker}/{@code farm.FarmerSpawnTicker}/{@code
 * mineshaft.MineshaftSpawnTicker}, which get their own specialized worker for those cases).
 */
public final class ResidentSpawnTicker {

    private static final int SCAN_INTERVAL_TICKS = 100; // 5 seconds, same cadence as GuardSpawnTicker.
    // Staggered 2026-10-12 against the other seven 100-tick full-world scan tickers across this
    // suite -- see FarmerSpawnTicker's own doc for the full "thundering herd" root cause.
    private static final int TICK_OFFSET = 50;

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
            fillResident(level, entry.getKey(), pos.pos());
        }
    }

    private void fillResident(ServerLevel level, UUID plotId, BlockPos signPos) {
        if (!(level.getBlockEntity(signPos) instanceof PlotConfigSignBlockEntity sign)
                || sign.settlementCoreId() == null
                || !(level.getEntity(sign.settlementCoreId()) instanceof GhostTownHallCoreEntity core)) {
            return; // Chunk not loaded, or the sign/core couldn't be resolved -- skip this scan.
        }
        PlotRecord plot = core.getPlots().stream().filter(p -> p.plotId().equals(plotId)).findFirst().orElse(null);
        if (plot == null || (plot.owner().isPresent() && !plot.npcHired())) {
            return; // Player-owned, and no hired NPC paid for -- no resident.
        }
        if (com.github.cerealklla.settlemynts.zone.PlotWorkerKind.resolve(plot) != com.github.cerealklla.settlemynts.zone.PlotWorkerKind.Kind.RESIDENT) {
            return; // Guardhouse/Town Hall get no worker at all, and Farm/Lumberyard/Mineshaft (by
            // zoneTypeId or an explicit PlotJob override) get their own specialized worker instead --
            // see PlotWorkerKind's own doc for why this single check replaced four separate ones.
        }

        // Real, confirmed-live duplicate-worker bug fixed 2026-10-12 (see
        // zone.PlotNpcDeathListener's own doc for the full root-cause trace, found via Mineshaft's
        // twin) -- this used to also check `level.getEntity(existing)` here and treat "not currently
        // found" as proof of death, which is only ever proof the resident's current chunk isn't
        // loaded *right now*, near-guaranteed right after a restart. A slot is now only ever cleared
        // by a real death; a non-null slot here always means "alive, don't touch" -- still doing the
        // idempotent profession backfill below when the resident happens to be loaded right now,
        // purely cosmetic and harmless to skip on a scan where it isn't.
        if (sign.residentVillagerId() != null) {
            if (level.getEntity(sign.residentVillagerId()) instanceof ResidentVillagerEntity living) {
                com.github.cerealklla.settlemynts.zone.NaturalVillagePlotGenerator.representativeProfessionFor(plot.zoneTypeId())
                        .ifPresent(profession -> living.setVillagerData(living.getVillagerData().withProfession(level.registryAccess(), profession)));
            }
            return; // Already filled.
        }

        // No finalizeSpawn call, deliberately -- that would let vanilla's own
        // assignProfessionWhenSpawned logic auto-claim a nearby job site/profession, which this
        // resident should never do (the Brain that would act on a profession is never ticked anyway
        // -- see ResidentVillagerEntity's own class doc). The profession itself is still set directly
        // below (2026-10-09, explicit request: "I'd like the NPCs... which are generated for a plot
        // to have the 'Job' for that plot too... if there's a relevant model they should be using
        // it") -- purely cosmetic (vanilla renders a profession-specific robe color off VillagerData
        // alone), safe precisely because the Brain never runs, so it can never turn into real
        // job-site-claiming or trading -- the same `representativeProfessionFor` lookup
        // NaturalVillagePlotGenerator already uses for natural villages' own villagers.
        ResidentVillagerEntity resident = new ResidentVillagerEntity(ModEntities.RESIDENT_VILLAGER.get(), level);
        // Real report, 2026-10-23: "you're spawning them inside the sign and then they get stuck" --
        // see FarmerSpawnTicker's own doc for the full root cause (the sign's solid block model).
        net.minecraft.core.BlockPos spawnPos = signPos.above();
        resident.setPos(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
        resident.setPlotIdentity(sign.settlementCoreId(), plotId);
        resident.setSpawnPos(spawnPos);
        com.github.cerealklla.settlemynts.zone.NaturalVillagePlotGenerator.representativeProfessionFor(plot.zoneTypeId())
                .ifPresent(profession -> resident.setVillagerData(resident.getVillagerData().withProfession(level.registryAccess(), profession)));
        level.addFreshEntity(resident);
        sign.setResidentVillagerId(resident.getUUID());
    }
}
