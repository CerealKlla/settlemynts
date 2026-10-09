package com.github.cerealklla.settlemynts.zone;

import java.util.Map;
import java.util.Optional;

import com.github.cerealklla.cartographyr.api.Cartography;
import com.github.cerealklla.cartographyr.geo.EntityId;

import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * Settlemynts' own half of natural-village discovery (plan "Natural settlements..." Part B) --
 * mirrors Cartographyr's own {@code settlement.SettlementListener} exactly (same chunk-load trigger,
 * same village-structure-tag filter, same "fires on every load, idempotency makes it safe" shape),
 * but runs independently rather than extending that class, since Cartographyr has no reason to know
 * about Settlemynts' {@link PlotRecord} concept. Idempotency here is "does this village already have
 * any plots generated for it, or is it already enqueued?" ({@link NaturalSettlementPlotStore#getPlots}
 * non-empty, or already in {@link NaturalVillagePlotPending}'s queue) rather than a separate
 * processed-structures index.
 *
 * <p>Depends on Cartographyr's own listener having already registered the village as a {@code
 * GeographicEntity} (via {@code Cartography.getEntityForStructure}) -- if it hasn't yet (e.g. load
 * order on the very first tick this chunk is ever seen), this simply finds nothing and tries again
 * next chunk load, same lazy-catch-up behavior Cartographyr's own listener relies on.
 *
 * <p>Doesn't generate plots itself -- enqueues the village into {@link NaturalVillagePlotPending},
 * processed by {@code NaturalVillagePlotGenerationTicker} once each piece's own chunk area has
 * actually loaded (see that class's own doc for why generating immediately produced wrong results: a
 * live test showed every single piece falling back to Private Residence, because the village's
 * *other* chunks hadn't actually generated their blocks/villagers yet at the moment its one origin
 * chunk loaded).
 */
public final class NaturalVillagePlotListener {

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        Map<Structure, StructureStart> starts = event.getChunk().getAllStarts();
        if (starts.isEmpty()) {
            return;
        }

        var structureRegistry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);

        for (Map.Entry<Structure, StructureStart> entry : starts.entrySet()) {
            StructureStart start = entry.getValue();
            if (!start.isValid()) {
                continue;
            }
            if (!structureRegistry.wrapAsHolder(entry.getKey()).is(StructureTags.VILLAGE)) {
                continue;
            }

            GlobalPos structureReference = new GlobalPos(level.dimension(), start.getChunkPos().getWorldPosition());
            Optional<EntityId> settlementEntityId = Cartography.getEntityForStructure(level, structureReference);
            if (settlementEntityId.isEmpty()) {
                continue; // Cartographyr hasn't discovered this village yet -- try again next load.
            }
            SettlementKey settlement = SettlementKey.of(level, settlementEntityId.get().value());
            if (!NaturalSettlementPlotStore.get(level.getServer()).getPlots(settlement).isEmpty()) {
                continue; // Already generated.
            }

            com.github.cerealklla.settlemynts.SettlemyntsMod.LOGGER.info(
                    "Natural village plot generation: enqueued settlement {} ({} piece(s))",
                    settlementEntityId.get().value(), start.getPieces().size());
            NaturalVillagePlotPending.enqueueIfAbsent(level, start, settlementEntityId.get());
        }
    }
}
