package com.github.cerealklla.settlemynts.zone;

import java.util.List;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;

/**
 * Adapts one natural village's slice of {@link NaturalSettlementPlotStore} to {@link PlotOwner}, so
 * {@link ShopSeeding} (and anything else written against a player-founded {@code
 * founding.GhostTownHallCoreEntity}) works unchanged for a naturally-spawned village too. {@link
 * #getUUID()} is a deterministic stand-in identity (no real Town Hall Core entity exists to own a
 * UUID here) -- nothing currently keys off it for a natural village, it exists only so this class can
 * satisfy the interface.
 */
public record NaturalSettlementPlotOwner(MinecraftServer server, SettlementKey settlement) implements PlotOwner {

    @Override
    public UUID getUUID() {
        return new UUID(settlement.worldSeed(), settlement.settlementEntityId());
    }

    @Override
    public List<PlotRecord> getPlots() {
        return NaturalSettlementPlotStore.get(server).getPlots(settlement);
    }

    @Override
    public void addPlot(PlotRecord plot) {
        NaturalSettlementPlotStore.get(server).addPlot(settlement, plot);
    }

    @Override
    public void updatePlot(PlotRecord updated) {
        NaturalSettlementPlotStore.get(server).updatePlot(settlement, updated);
    }
}
