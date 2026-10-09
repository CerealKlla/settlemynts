package com.github.cerealklla.settlemynts.construction;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import com.github.cerealklla.settlemynts.api.Settlemynts;
import com.github.cerealklla.settlemynts.zone.ShopResource;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * "Buy a shortfall of some {@code GenericResource} from the nearby settlement economy" -- added
 * 2026-10-09 for {@link PlotTierUpgradeFunding}, Settlemynts' own native equivalent of Lyfe's
 * {@code structure.MarketPurchasing} (that class calls back into Settlemynts through a bridge; this
 * one already lives here, so it calls {@code api.Settlemynts} directly). Same pricing rules,
 * unchanged from that original spec: the home settlement's own average listed price at {@link
 * #LOCAL_MARKUP}x, or -- if nobody in the home settlement sells it at all -- the nearest settlement
 * that does, at {@link #NEIGHBOR_MARKUP}x their price plus a flat transport fee per {@link
 * #TRANSPORT_BUNDLE_SIZE}-item bundle (rounded up for a partial bundle).
 */
public final class PlotTierMarketPurchasing {

    private static final double NEIGHBOR_SEARCH_RADIUS_BLOCKS = 5000;
    private static final int TRANSPORT_FEE_PER_BUNDLE = 100;
    private static final int TRANSPORT_BUNDLE_SIZE = 50;
    private static final int LOCAL_MARKUP = 3;
    private static final int NEIGHBOR_MARKUP = 4;

    private PlotTierMarketPurchasing() {
    }

    public record Purchase(ResourceCost entry, int quantity, UUID sellerPlotId, int buyerCharge) {
    }

    /** {@code null} if nobody in the home settlement or any nearby settlement sells this resource at all. */
    public static Purchase resolvePurchase(ServerLevel level, UUID homeSettlementCoreId, BlockPos originPos, ResourceCost entry, int quantity) {
        ShopResource resource = ShopResource.ofTag(entry.resource().tag());
        OptionalInt localPrice = Settlemynts.getAverageSettlementPrice(level, homeSettlementCoreId, resource);
        if (localPrice.isPresent()) {
            Optional<UUID> sellerPlot = Settlemynts.findSellingPlot(level, homeSettlementCoreId, resource);
            if (sellerPlot.isPresent()) {
                return new Purchase(entry, quantity, sellerPlot.get(), quantity * localPrice.getAsInt() * LOCAL_MARKUP);
            }
        }
        for (Settlemynts.SettlementHandle neighbor : Settlemynts.findNearbySettlements(level, originPos, NEIGHBOR_SEARCH_RADIUS_BLOCKS)) {
            if (neighbor.settlementCoreId().equals(homeSettlementCoreId)) {
                continue;
            }
            OptionalInt neighborPrice = Settlemynts.getAverageSettlementPrice(level, neighbor.settlementCoreId(), resource);
            if (neighborPrice.isEmpty()) {
                continue;
            }
            Optional<UUID> sellerPlot = Settlemynts.findSellingPlot(level, neighbor.settlementCoreId(), resource);
            if (sellerPlot.isEmpty()) {
                continue;
            }
            int bundles = (int) Math.ceil(quantity / (double) TRANSPORT_BUNDLE_SIZE);
            int transportFee = bundles * TRANSPORT_FEE_PER_BUNDLE;
            int charge = quantity * neighborPrice.getAsInt() * NEIGHBOR_MARKUP + transportFee;
            return new Purchase(entry, quantity, sellerPlot.get(), charge);
        }
        return null;
    }
}
