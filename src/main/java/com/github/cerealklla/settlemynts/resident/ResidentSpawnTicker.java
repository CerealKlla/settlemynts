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
 * {@code construction.NpcAutoFundingTicker}'s identical check) is eligible, regardless of zone
 * type -- except a Guardhouse (real report, 2026-10-05: "do not spawn an NPC at the guardhouse,
 * they already spawn actual guards" -- this is exactly the "worth revisiting" case this doc used
 * to flag as open; it's now resolved, a Guardhouse plot never gets a resident), and, same day,
 * Lumberyard/Farm plots once their own specialized worker NPCs (see {@code
 * lumberyard.LumberjackSpawnTicker}/{@code farm.FarmerSpawnTicker}) replaced the generic resident
 * for those two zone types too.
 */
public final class ResidentSpawnTicker {

    private static final int SCAN_INTERVAL_TICKS = 100; // 5 seconds, same cadence as GuardSpawnTicker.

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % SCAN_INTERVAL_TICKS != 0) {
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
        if (plot == null || plot.owner().isPresent()) {
            return; // Player-owned -- no resident.
        }
        if (plot.zoneTypeId().equals(com.github.cerealklla.settlemynts.guardhouse.GuardhouseConstants.GUARDHOUSE_ZONE_TYPE_ID)) {
            return; // Guardhouse already has its own guards -- no resident villager too.
        }
        if (plot.zoneTypeId().equals(net.minecraft.resources.Identifier.fromNamespaceAndPath("blueprynts", "lumberyard"))
                || plot.zoneTypeId().equals(net.minecraft.resources.Identifier.fromNamespaceAndPath("blueprynts", "farm"))) {
            return; // 2026-10-05: these get their own specialized worker instead (see lumberyard/farm packages).
        }

        UUID existing = sign.residentVillagerId();
        if (existing != null) {
            if (level.getEntity(existing) instanceof ResidentVillagerEntity living && living.isAlive()) {
                return; // Already filled.
            }
            sign.setResidentVillagerId(null);
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
        resident.setPos(signPos.getX() + 0.5, signPos.getY(), signPos.getZ() + 0.5);
        resident.setPlotIdentity(sign.settlementCoreId(), plotId);
        com.github.cerealklla.settlemynts.zone.NaturalVillagePlotGenerator.representativeProfessionFor(plot.zoneTypeId())
                .ifPresent(profession -> resident.setVillagerData(resident.getVillagerData().withProfession(level.registryAccess(), profession)));
        level.addFreshEntity(resident);
        sign.setResidentVillagerId(resident.getUUID());
    }
}
