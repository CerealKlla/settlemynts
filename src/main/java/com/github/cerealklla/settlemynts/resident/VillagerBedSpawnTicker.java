package com.github.cerealklla.settlemynts.resident;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.github.cerealklla.cartographyr.api.Cartography;
import com.github.cerealklla.cartographyr.geo.EntityId;
import com.github.cerealklla.cartographyr.geo.GeographicEntity;
import com.github.cerealklla.cartographyr.geo.Geometry;
import com.github.cerealklla.settlemynts.zone.NaturalSettlementPlotStore;
import com.github.cerealklla.settlemynts.zone.PlotRecord;
import com.github.cerealklla.settlemynts.zone.SettlementKey;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Discovers every real bed in each known natural village and keeps each one filled with exactly one
 * living plain {@code Villager} -- explicit user redesign, 2026-11-17 ("use the beds in the NPC
 * settlement to bind NPCs to spawn points, not the settlement itself... each bed in the settlement
 * gets an NPC"), replacing the old death-position/settlement-margin respawn queue entirely ({@code
 * VillagerRespawnQueue}/{@code VillagerRespawnTicker}/{@code PendingVillagerRespawn(Index)}, all
 * deleted). Real motivating gap found investigating a "no NPCs are respawning" report the same day:
 * the old system respawned a villager at wherever it actually died -- often deep in a cave, off a
 * cliff, or wherever a chase happened to end -- which is frequently a terrible (or, past the old
 * 20-block settlement margin, never-even-queued) spot. Binding to a bed guarantees every respawn
 * lands somewhere sensible, inside an actual house, every time. Same slot-ownership model {@code
 * guardhouse.GuardKitStandEntity}/{@code GuardSpawnTicker} already use for guards; a bed just has no
 * block entity of its own to carry that state directly, so it lives in {@link BedSpawnSlotIndex}
 * instead.
 *
 * <p>Discovery is anchored per-plot, not per-settlement -- a settlement's own registered polygon can
 * sprawl across a cliff (a real such case is what surfaced this whole redesign), so one settlement-
 * wide search center/radius would either miss far-flung beds or need to be impractically large.
 * Each plot's own small, already-registered Cartographyr polygon gives a tight, sensible per-
 * building search anchor instead, snapped to the real terrain surface there -- vanilla's own POI
 * search is a true 3D radius (see {@code PoiManager#getInRange}), so an anchor Y actually close to
 * the building matters, not just the right X/Z.
 *
 * <p>Same cheap periodic-scan shape as {@code guardhouse.GuardSpawnTicker}/{@code
 * resident.ResidentSpawnTicker} otherwise -- discovery and fill both happen on the same 5-second
 * pass, gated per plot/bed on that chunk being loaded (same accepted scaling simplification every
 * other full-world-scanning ticker in this mod already carries).
 */
public final class VillagerBedSpawnTicker {

    private static final int SCAN_INTERVAL_TICKS = 100; // 5 seconds, same cadence as the suite's other spawn tickers.
    // Freed by this feature deleting the old VillagerRespawnTicker, which owned this offset.
    private static final int TICK_OFFSET = 62;
    private static final int BED_SEARCH_RADIUS_BLOCKS = 24; // Comfortably covers one natural-village building/piece.
    private static final int ADOPT_NEARBY_UNBOUND_RADIUS_BLOCKS = 8;
    // Package-private, not private -- VillagerDeathListener/VillagerConversionListener (same
    // package) also vacate a slot on population loss and need this same delay value.
    static final long RESPAWN_DELAY_TICKS = 5L * 60L * 20L; // 5 minutes -- same pacing the old death-position system used.

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if ((server.getTickCount() + TICK_OFFSET) % SCAN_INTERVAL_TICKS != 0) {
            return;
        }
        ServerLevel overworld = server.overworld(); // Natural settlements are an overworld-only concept -- see zone.SettlementKey's own doc.
        BedSpawnSlotIndex index = BedSpawnSlotIndex.get(server);
        for (Map.Entry<SettlementKey, List<PlotRecord>> entry : NaturalSettlementPlotStore.get(server).all().entrySet()) {
            long settlementEntityId = entry.getKey().settlementEntityId();
            for (PlotRecord plot : entry.getValue()) {
                discoverBeds(overworld, index, settlementEntityId, plot);
            }
        }
        for (BedSpawnSlot slot : index.all()) {
            fillSlot(overworld, index, slot);
        }
    }

    private void discoverBeds(ServerLevel level, BedSpawnSlotIndex index, long settlementEntityId, PlotRecord plot) {
        Optional<GeographicEntity> plotEntity = Cartography.getEntity(level, new EntityId(plot.cartographyrPlotEntityId()));
        if (plotEntity.isEmpty() || !(plotEntity.get().geometry() instanceof Geometry.Polygon polygon) || polygon.vertices().isEmpty()) {
            return; // Not loaded/resolvable this scan -- try again next time.
        }
        long sumX = 0, sumZ = 0;
        for (Geometry.Polygon.Vertex vertex : polygon.vertices()) {
            sumX += vertex.x();
            sumZ += vertex.z();
        }
        int centerX = (int) (sumX / polygon.vertices().size());
        int centerZ = (int) (sumZ / polygon.vertices().size());
        if (!level.hasChunk(centerX >> 4, centerZ >> 4)) {
            return; // Chunk not loaded -- skip until it is, same as every other ticker in this mod.
        }
        int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, centerX, centerZ);
        BlockPos anchor = new BlockPos(centerX, groundY, centerZ);
        List<BlockPos> beds = level.getPoiManager()
                .findAll(holder -> holder.is(PoiTypes.HOME), pos -> true, anchor, BED_SEARCH_RADIUS_BLOCKS, PoiManager.Occupancy.ANY)
                .toList();
        for (BlockPos bedPos : beds) {
            if (index.isTracked(bedPos)) {
                continue;
            }
            // A bed newly discovered right next to an already-alive, not-yet-bed-bound villager
            // adopts it instead of spawning a duplicate on top of it -- this is what sweeps any
            // *existing* population (a natural village's villagers from before this feature existed)
            // into the new system on first scan, rather than doubling it.
            Optional<UUID> adopted = findAdoptableVillager(level, index, bedPos);
            BedSpawnSlot slot = new BedSpawnSlot(level.dimension().identifier(), bedPos, settlementEntityId,
                    adopted, level.getGameTime());
            index.put(slot);
        }
    }

    private Optional<UUID> findAdoptableVillager(ServerLevel level, BedSpawnSlotIndex index, BlockPos bedPos) {
        AABB area = new AABB(bedPos).inflate(ADOPT_NEARBY_UNBOUND_RADIUS_BLOCKS);
        for (Villager villager : level.getEntitiesOfClass(Villager.class, area, v -> v.getClass() == Villager.class && v.isAlive())) {
            if (!index.isBoundElsewhere(villager.getUUID(), bedPos)) {
                return Optional.of(villager.getUUID());
            }
        }
        return Optional.empty();
    }

    private void fillSlot(ServerLevel level, BedSpawnSlotIndex index, BedSpawnSlot slot) {
        if (!level.hasChunk(slot.bedPos().getX() >> 4, slot.bedPos().getZ() >> 4)) {
            return; // Try again once loaded.
        }
        if (slot.villagerId().isPresent()) {
            if (level.getEntity(slot.villagerId().get()) instanceof Villager villager
                    && villager.getClass() == Villager.class && villager.isAlive()) {
                return; // Still alive and bound -- nothing to do.
            }
            // Chunk IS loaded (checked above) and the bound villager genuinely isn't there alive --
            // a real death/loss, not just "its chunk happens to be unloaded right now." Vacate and
            // start this slot's respawn timer.
            index.put(slot.vacated(level.getGameTime() + RESPAWN_DELAY_TICKS));
            return;
        }
        if (level.getGameTime() < slot.respawnReadyAtGameTime()) {
            return;
        }
        // Spawns beside/above the bed, not inside its own partial-height hitbox -- same "don't spawn
        // inside the flat object itself" precedent FarmerSpawnTicker's own doc already established
        // for signs.
        BlockPos spawnPos = slot.bedPos().above();
        Villager villager = new Villager(EntityType.VILLAGER, level);
        villager.setPos(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
        level.addFreshEntity(villager);
        index.put(slot.withVillager(villager.getUUID()));
    }
}
