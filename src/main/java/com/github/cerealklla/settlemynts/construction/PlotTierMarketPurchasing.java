package com.github.cerealklla.settlemynts.construction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.github.cerealklla.blueprynts.blueprint.GenericResource;
import com.github.cerealklla.settlemynts.api.Settlemynts;
import com.github.cerealklla.settlemynts.zone.ShopResource;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/**
 * "Buy a shortfall of some {@code GenericResource} from the nearby settlement economy" -- Settlemynts'
 * own native equivalent of Lyfe's {@code structure.MarketPurchasing}. Widened 2026-10-09 (explicit
 * request/correction after the first live test) from "first seller found, at the settlement's
 * average price" into a real cheapest-first, stock-aware multi-seller allocation: "using the
 * cheapest of all plot store prices within the settlement, and keeping in mind available stock at
 * those stores... if not enough resources are available... the appropriate buttons should be
 * disabled." Local sellers (this settlement) are always exhausted, cheapest first, before ever
 * falling back to a neighbor settlement (same two-phase shape the original design already had) --
 * neighbor sellers are likewise tried cheapest-effective-price first. Markup is unchanged from the
 * original spec: {@link #LOCAL_MARKUP}x the local seller's own price, or {@link #NEIGHBOR_MARKUP}x
 * a neighbor's price plus a flat transport fee per {@link #TRANSPORT_BUNDLE_SIZE}-item bundle.
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

    /** {@code quantityFilled} may be less than requested if the whole settlement economy (local + every neighbor) can't fully cover it -- the caller's cue to disable whichever button needed this amount. */
    public record Allocation(List<Purchase> purchases, int quantityFilled, int totalCost) {
        public boolean fullyCovered(int needed) {
            return quantityFilled >= needed;
        }
    }

    private record Offer(UUID sellerPlotId, int effectivePricePerUnit, boolean local) {
    }

    /** Cheapest-first, stock-aware allocation across the whole settlement economy for one resource entry. */
    public static Allocation planPurchase(ServerLevel level, UUID homeSettlementCoreId, BlockPos originPos, ResourceCost entry, int neededQuantity) {
        ShopResource resource = ShopResource.ofTag(entry.resource().tag());
        List<Offer> offers = new ArrayList<>();
        for (Settlemynts.PlotPrice seller : Settlemynts.findSellingPlots(level, homeSettlementCoreId, resource)) {
            offers.add(new Offer(seller.plotId(), seller.pricePerUnit() * LOCAL_MARKUP, true));
        }
        for (Settlemynts.SettlementHandle neighbor : Settlemynts.findNearbySettlements(level, originPos, NEIGHBOR_SEARCH_RADIUS_BLOCKS)) {
            if (neighbor.settlementCoreId().equals(homeSettlementCoreId)) {
                continue;
            }
            for (Settlemynts.PlotPrice seller : Settlemynts.findSellingPlots(level, neighbor.settlementCoreId(), resource)) {
                offers.add(new Offer(seller.plotId(), seller.pricePerUnit() * NEIGHBOR_MARKUP, false));
            }
        }
        offers.sort(Comparator.comparingInt(Offer::effectivePricePerUnit));

        List<Purchase> purchases = new ArrayList<>();
        int remaining = neededQuantity;
        int totalCost = 0;
        for (Offer offer : offers) {
            if (remaining <= 0) {
                break;
            }
            int stock = countAvailable(Settlemynts.resolvePlotBoxes(level, offer.sellerPlotId()), entry.resource());
            if (stock <= 0) {
                continue;
            }
            int take = Math.min(remaining, stock);
            int cost = take * offer.effectivePricePerUnit();
            if (!offer.local()) {
                cost += (int) Math.ceil(take / (double) TRANSPORT_BUNDLE_SIZE) * TRANSPORT_FEE_PER_BUNDLE;
            }
            purchases.add(new Purchase(entry, take, offer.sellerPlotId(), cost));
            totalCost += cost;
            remaining -= take;
        }
        return new Allocation(purchases, neededQuantity - remaining, totalCost);
    }

    static int countAvailable(List<Container> boxes, GenericResource resource) {
        int total = 0;
        for (Container box : boxes) {
            for (int slot = 0; slot < box.getContainerSize(); slot++) {
                ItemStack stack = box.getItem(slot);
                if (!stack.isEmpty() && resource.matches(stack.getItem())) {
                    total += stack.getCount();
                }
            }
        }
        return total;
    }
}
