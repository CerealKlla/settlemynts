package com.github.cerealklla.settlemynts.zone;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Save-wide store of {@link PlotRecord}s belonging to naturally-spawned villages, keyed by {@link
 * SettlementKey} (world seed + Cartographyr {@code EntityId} -- see that record's own doc for why the
 * bare id alone isn't a safe key) -- added 2026-10-08 for auto-generated natural-village plots (see
 * {@link NaturalVillagePlotGenerator}). Mirrors {@code founding.GhostTownHallCoreEntity}'s own
 * in-memory {@code plots} list, just persisted directly instead of riding along on a real {@code
 * Entity}'s save data -- a natural village has no Town Hall Core (or any other entity) to attach
 * plots to. {@link NaturalSettlementPlotOwner} adapts a single village's slice of this store to the
 * shared {@link PlotOwner} interface.
 */
public final class NaturalSettlementPlotStore extends SavedData {

    public static final SavedDataType<NaturalSettlementPlotStore> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "natural_settlement_plots"),
            NaturalSettlementPlotStore::new,
            codec());

    private final Map<SettlementKey, List<PlotRecord>> bySettlement;
    // Settlement -> zone type id -> the one Shop every plot of that type in that settlement shares
    // (added 2026-10-08, explicit user request: "if there are multiple of the same type of plot in
    // these settlements just let them share the same inventory" -- these are all NPC-owned, nobody
    // manages an individual building's own Shop here, so one Farm Shop per settlement makes more
    // sense than N separate, identically-stocked ones).
    private final Map<SettlementKey, Map<Identifier, UUID>> sharedShops;

    NaturalSettlementPlotStore() {
        this(new HashMap<>(), new HashMap<>());
    }

    private NaturalSettlementPlotStore(Map<SettlementKey, List<PlotRecord>> bySettlement, Map<SettlementKey, Map<Identifier, UUID>> sharedShops) {
        this.bySettlement = bySettlement;
        this.sharedShops = sharedShops;
    }

    private record Entry(SettlementKey settlement, List<PlotRecord> plots, Map<Identifier, UUID> sharedShops) {
        static final Codec<Map<Identifier, UUID>> SHARED_SHOPS_CODEC = Codec.unboundedMap(Identifier.CODEC, net.minecraft.core.UUIDUtil.CODEC);
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                SettlementKey.CODEC.fieldOf("settlement").forGetter(Entry::settlement),
                PlotRecord.CODEC.listOf().fieldOf("plots").forGetter(Entry::plots),
                SHARED_SHOPS_CODEC.optionalFieldOf("shared_shops", Map.of()).forGetter(Entry::sharedShops)
        ).apply(i, Entry::new));
    }

    private static Codec<NaturalSettlementPlotStore> codec() {
        return Codec.list(Entry.CODEC).xmap(
                entries -> {
                    Map<SettlementKey, List<PlotRecord>> map = new HashMap<>();
                    Map<SettlementKey, Map<Identifier, UUID>> shops = new HashMap<>();
                    for (Entry entry : entries) {
                        map.put(entry.settlement(), new ArrayList<>(entry.plots()));
                        shops.put(entry.settlement(), new HashMap<>(entry.sharedShops()));
                    }
                    return new NaturalSettlementPlotStore(map, shops);
                },
                data -> {
                    List<Entry> entries = new ArrayList<>(data.bySettlement.size());
                    data.bySettlement.forEach((key, plots) -> entries.add(new Entry(key, plots, data.sharedShops.getOrDefault(key, Map.of()))));
                    return entries;
                });
    }

    public static NaturalSettlementPlotStore get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    public List<PlotRecord> getPlots(SettlementKey settlement) {
        return List.copyOf(bySettlement.getOrDefault(settlement, List.of()));
    }

    public void addPlot(SettlementKey settlement, PlotRecord plot) {
        bySettlement.computeIfAbsent(settlement, k -> new ArrayList<>()).add(plot);
        setDirty();
    }

    public void updatePlot(SettlementKey settlement, PlotRecord updated) {
        List<PlotRecord> plots = bySettlement.get(settlement);
        if (plots == null) {
            return;
        }
        for (int i = 0; i < plots.size(); i++) {
            if (plots.get(i).plotId().equals(updated.plotId())) {
                plots.set(i, updated);
                setDirty();
                return;
            }
        }
    }

    /** Every tracked natural village's plots -- see {@code resident.VillagerRespawnTicker}. */
    public Map<SettlementKey, List<PlotRecord>> all() {
        return Map.copyOf(bySettlement);
    }

    /** The shared Shop for this settlement+zone-type pair, if one's already been created -- see {@link #sharedShops}'s own doc. */
    public Optional<UUID> getSharedShop(SettlementKey settlement, Identifier zoneTypeId) {
        Map<Identifier, UUID> byZone = sharedShops.get(settlement);
        return byZone == null ? Optional.empty() : Optional.ofNullable(byZone.get(zoneTypeId));
    }

    public void putSharedShop(SettlementKey settlement, Identifier zoneTypeId, UUID shopId) {
        sharedShops.computeIfAbsent(settlement, k -> new HashMap<>()).put(zoneTypeId, shopId);
        setDirty();
    }

    /**
     * Reverse lookup by {@code plotId} -- there's no index for it (a linear scan over however many
     * natural-village plots exist, fine at this suite's scale, same precedent as {@code
     * MinimapTracker}'s own whole-dimension scan), used to resolve a {@link ShopAnchor.Npc}'s stored
     * plot id back to its owning settlement. Returns the owning settlement's key paired with the
     * matching plot.
     */
    public Optional<Map.Entry<SettlementKey, PlotRecord>> findByPlotId(UUID plotId) {
        for (Map.Entry<SettlementKey, List<PlotRecord>> entry : bySettlement.entrySet()) {
            for (PlotRecord plot : entry.getValue()) {
                if (plot.plotId().equals(plotId)) {
                    return Optional.of(new AbstractMap.SimpleEntry<>(entry.getKey(), plot));
                }
            }
        }
        return Optional.empty();
    }
}
