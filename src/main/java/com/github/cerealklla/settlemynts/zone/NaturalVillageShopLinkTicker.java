package com.github.cerealklla.settlemynts.zone;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % SCAN_INTERVAL_TICKS != 0) {
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
            if (villager.getVillagerData().profession().is(VillagerProfession.NONE)) {
                idleCandidates.add(villager);
            }
            if (villager.getPersistentData().contains(NaturalVillagePlotGenerator.PLOT_ID_TAG)) {
                continue; // Already linked.
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
            chosen.setVillagerData(chosen.getVillagerData().withProfession(level.registryAccess(), profession.get()));
            chosen.getPersistentData().putIntArray(NaturalVillagePlotGenerator.PLOT_ID_TAG, UUIDUtil.uuidToIntArray(match.plotId()));
        }
    }
}
