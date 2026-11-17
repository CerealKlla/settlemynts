package com.github.cerealklla.settlemynts.zone;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.github.cerealklla.cartographyr.api.Cartography;
import com.github.cerealklla.cartographyr.geo.EntityId;
import com.github.cerealklla.cartographyr.geo.GeographicEntity;
import com.github.cerealklla.cartographyr.geo.Geometry;

import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Continuously tags *every* villager in a natural settlement whose profession maps to a Zone Type
 * that settlement actually has a plot (and so a Shop) for -- explicit user design, 2026-10-08: "if
 * there are 3 farmers walking around, a player can interact with all of them to access their shared
 * shop... I want all NPCs of that job type to share the shop." Replaces the earlier per-piece "find
 * the one job-site-claiming villager" approach entirely -- simpler, and matches what was actually
 * asked: linking is a settlement-wide, profession-driven property, not tied to any specific building.
 *
 * <p><b>Force-assigns a profession, added 2026-10-08</b> -- a live test (30+ minutes across 4 towns,
 * methodically inspecting every building) found that not one villager had ever claimed a job on its
 * own, and nothing in this suite touches vanilla's own job-site-claiming AI to explain why; rather
 * than keep waiting on an AI system outside this mod's control, the user asked to just pick a random
 * idle villager per Zone Type and directly assign it that profession, guaranteeing every Shop a
 * worker from the moment its settlement is scanned. This is in addition to (not instead of) tagging
 * every villager who *does* end up with a matching profession, vanilla-claimed or not.
 *
 * <p>Villages are overworld-only, so this only ever scans {@code server.overworld()}. Already-tagged
 * villagers are skipped cheaply (persistent-data check) so a settlement with many villagers doesn't
 * re-resolve the same ones every pass.
 */
public final class NaturalVillageShopLinkTicker {

    private static final int SCAN_INTERVAL_TICKS = 100; // 5 seconds, same cadence as ResidentSpawnTicker.
    // Staggered 2026-10-12 against the other seven 100-tick full-world scan tickers across this
    // suite -- see FarmerSpawnTicker's own doc for the full "thundering herd" root cause.
    private static final int TICK_OFFSET = 75;

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if ((server.getTickCount() + TICK_OFFSET) % SCAN_INTERVAL_TICKS != 0) {
            return;
        }
        ServerLevel level = server.overworld();
        if (level == null) {
            return;
        }
        for (Map.Entry<SettlementKey, List<PlotRecord>> entry : NaturalSettlementPlotStore.get(server).all().entrySet()) {
            linkSettlement(level, entry.getKey(), entry.getValue());
        }
    }

    private void linkSettlement(ServerLevel level, SettlementKey settlement, List<PlotRecord> plots) {
        Optional<GeographicEntity> settlementEntity = Cartography.getEntity(level, new EntityId(settlement.settlementEntityId()));
        if (settlementEntity.isEmpty() || !(settlementEntity.get().geometry() instanceof Geometry.Polygon polygon) || polygon.vertices().isEmpty()) {
            return;
        }
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Geometry.Polygon.Vertex vertex : polygon.vertices()) {
            minX = Math.min(minX, vertex.x());
            maxX = Math.max(maxX, vertex.x());
            minZ = Math.min(minZ, vertex.z());
            maxZ = Math.max(maxZ, vertex.z());
        }
        AABB bounds = new AABB(minX, level.getMinY(), minZ, maxX, level.getMaxY(), maxZ);

        List<Villager> villagers = level.getEntitiesOfClass(Villager.class, bounds, v -> v.getClass() == Villager.class);
        Set<Identifier> satisfiedZoneTypes = new HashSet<>();
        List<Villager> idleCandidates = new ArrayList<>();

        for (Villager villager : villagers) {
            if (villager.getPersistentData().contains(NaturalVillagePlotGenerator.PLOT_ID_TAG)) {
                // Already linked -- re-assert its profession every scan instead of only at link time.
                // Real bug, 2026-11-17 (and its real root cause, found the same day after a live report
                // that this reassert-every-5-seconds fix didn't actually stop the flicker): vanilla's
                // own CORE brain package runs `ResetProfession` EVERY TICK for every villager (confirmed
                // against the decompiled source, `VillagerGoalPackages#getCorePackage`) -- it reverts a
                // non-NONE/NITWIT profession straight back to NONE the instant a villager has no real
                // JOB_SITE memory AND villagerXp == 0 AND level <= 1, which describes every villager
                // this ticker force-assigns (no real POI claim is ever granted). A 5-second reassert
                // ticker can only ever lose that race -- vanilla wins it back on literally the very next
                // tick, which is exactly the "flickering multiple times a minute" symptom. The real fix
                // (see reassertProfession's own doc) is defeating ResetProfession's own guard condition
                // directly rather than fighting it reactively.
                reassertProfession(level, plots, villager).ifPresent(linkedPlot -> satisfiedZoneTypes.add(linkedPlot.zoneTypeId()));
                continue;
            }
            if (villager.getVillagerData().profession().is(VillagerProfession.NONE)) {
                idleCandidates.add(villager);
            }
            Optional<Identifier> zoneTypeId = NaturalVillagePlotGenerator.zoneTypeForProfession(villager.getVillagerData().profession());
            if (zoneTypeId.isEmpty()) {
                continue; // No profession yet, or one this mod has no Zone Type mapping for.
            }
            satisfiedZoneTypes.add(zoneTypeId.get());
            PlotRecord match = plots.stream().filter(p -> p.zoneTypeId().equals(zoneTypeId.get())).findFirst().orElse(null);
            if (match == null) {
                continue; // This settlement has no plot of that type -- nothing to link to.
            }
            villager.getPersistentData().putIntArray(NaturalVillagePlotGenerator.PLOT_ID_TAG, UUIDUtil.uuidToIntArray(match.plotId()));
        }

        Set<Identifier> neededZoneTypes = new HashSet<>();
        for (PlotRecord plot : plots) {
            if (!satisfiedZoneTypes.contains(plot.zoneTypeId())
                    && NaturalVillagePlotGenerator.representativeProfessionFor(plot.zoneTypeId()).isPresent()) {
                neededZoneTypes.add(plot.zoneTypeId());
            }
        }
        if (neededZoneTypes.isEmpty() || idleCandidates.isEmpty()) {
            return;
        }
        Collections.shuffle(idleCandidates);
        int nextCandidate = 0;
        for (Identifier zoneTypeId : neededZoneTypes) {
            if (nextCandidate >= idleCandidates.size()) {
                break; // Ran out of idle villagers -- the rest wait for next pass (a death/respawn, etc.).
            }
            Optional<ResourceKey<VillagerProfession>> profession = NaturalVillagePlotGenerator.representativeProfessionFor(zoneTypeId);
            PlotRecord match = plots.stream().filter(p -> p.zoneTypeId().equals(zoneTypeId)).findFirst().orElse(null);
            if (profession.isEmpty() || match == null) {
                continue;
            }
            Villager chosen = idleCandidates.get(nextCandidate++);
            assignProfession(level, chosen, profession.get());
            chosen.getPersistentData().putIntArray(NaturalVillagePlotGenerator.PLOT_ID_TAG, UUIDUtil.uuidToIntArray(match.plotId()));
        }
    }

    /**
     * Re-applies an already-linked villager's expected profession if vanilla's own job-site
     * validation has since reverted it to {@code NONE} -- see the real-bug writeup at this method's
     * own call site above. Returns the resolved linked {@link PlotRecord} (if any), so the caller can
     * still count this zone type as satisfied even though this villager is skipped from the idle-
     * candidate scan entirely.
     */
    private Optional<PlotRecord> reassertProfession(ServerLevel level, List<PlotRecord> plots, Villager villager) {
        Optional<UUID> plotId = villager.getPersistentData().getIntArray(NaturalVillagePlotGenerator.PLOT_ID_TAG)
                .map(UUIDUtil::uuidFromIntArray);
        Optional<PlotRecord> plot = plotId.flatMap(id -> plots.stream().filter(p -> p.plotId().equals(id)).findFirst());
        plot.ifPresent(p -> NaturalVillagePlotGenerator.representativeProfessionFor(p.zoneTypeId()).ifPresent(profession -> {
            if (!villager.getVillagerData().profession().is(profession)) {
                assignProfession(level, villager, profession);
            }
        }));
        return plot;
    }

    /**
     * The actual fix for the flicker, 2026-11-17 -- see the real-root-cause writeup at this class's
     * own call sites above. Vanilla's `ResetProfession` behavior only ever fires when a villager's
     * {@code villagerXp} is still exactly 0 (among its other conditions) -- confirmed against the
     * decompiled source. A real villager naturally crosses that threshold the first time it ever
     * successfully trades, but one of this ticker's force-assigned villagers never does (nothing
     * here ever opens a real trade with it), so it stays stuck at the vanilla default (0) forever
     * unless something else bumps it. Setting it to 1 here permanently defeats that guard with no
     * other observable effect (xp only otherwise matters for the trade-restock-unlock curve, which
     * these villagers don't meaningfully participate in anyway) -- cheaper and more robust than
     * fabricating a real {@code JOB_SITE} POI claim, which would need a matching physical POI block
     * to exist and be free, something this ticker has no guarantee of.
     */
    private void assignProfession(ServerLevel level, Villager villager, ResourceKey<VillagerProfession> profession) {
        villager.setVillagerData(villager.getVillagerData().withProfession(level.registryAccess(), profession));
        if (villager.getVillagerXp() == 0) {
            villager.setVillagerXp(1);
        }
    }
}
