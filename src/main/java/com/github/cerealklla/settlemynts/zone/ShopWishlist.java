package com.github.cerealklla.settlemynts.zone;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;

import com.github.cerealklla.settlemynts.api.Settlemynts;

/**
 * A shop's public "what do I still need" list (2026-10-10, explicit user request: "I'd like shops to
 * have a public wishlist, which shows what the shop needs in order to accomplish its preferred
 * inventory"). Every entry here is exactly a {@link PlannedInventoryTarget} the plot is currently
 * below -- the same deficit definition {@link PlannedInventoryClearing#settleResource}'s own buyer
 * side already uses for the midnight auto-buy, just computed on demand for any player who asks,
 * not only at the midnight tick. Deliberately read-only/side-effect-free -- unlike the midnight
 * engine, this never moves any items or gold itself.
 */
public final class ShopWishlist {

    private ShopWishlist() {
    }

    /** 10 minutes -- how long {@link WishlistOfferTracker#grant} keeps the player-initiated premium-sell offer open once asked for. */
    public static final long OFFER_WINDOW_TICKS = 20L * 60 * 10;

    /** 10% above {@code normalPricePerUnit}, always at least 1 higher so a cheap item still has a real premium. */
    public static int premiumPrice(int normalPricePerUnit) {
        return normalPricePerUnit + Math.max(1, Math.round(normalPricePerUnit * 0.10F));
    }

    public record DeficitEntry(ShopResource resource, int quantityNeeded) {
    }

    /** Every {@link PlannedInventoryTarget} this plot is currently below, with how many units short. Empty if the plot has no Planned Inventory at all, or nothing's currently short. */
    public static List<DeficitEntry> computeDeficits(ServerLevel level, PlotRecord plot) {
        List<DeficitEntry> deficits = new ArrayList<>();
        if (plot.plannedInventory().isEmpty()) {
            return deficits;
        }
        List<Container> boxes = Settlemynts.resolvePlotBoxes(level, plot.plotId());
        for (PlannedInventoryTarget target : plot.plannedInventory()) {
            ShopResource resource = target.isTag()
                    ? ShopResource.ofTag(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, target.resourceKey()))
                    : ShopResource.ofItem(target.resourceKey());
            int currentStock = ContainerWithdraw.countAvailable(boxes, resource);
            if (currentStock < target.targetCount()) {
                deficits.add(new DeficitEntry(resource, target.targetCount() - currentStock));
            }
        }
        return deficits;
    }

    /** Whether {@code plot} currently has any deficit at all -- cheap short-circuit for proximity checks that don't need the full list. */
    public static boolean hasAnyDeficit(ServerLevel level, PlotRecord plot) {
        if (plot.plannedInventory().isEmpty()) {
            return false;
        }
        return !computeDeficits(level, plot).isEmpty();
    }

    /** The "normal" per-unit price a deficit resource is worth -- the Zone Type catalog's own suggested price (see {@code ShopSeeding#suggestedPriceFor}'s own doc for why this, not a real Shop listing, is the right baseline: a shop buying raw materials to craft with usually has no sell listing for them at all). Never zero -- falls back to 1 so a premium can always be computed. */
    public static int normalPriceFor(ServerLevel level, PlotRecord plot, BlockPos plotAnchor, ShopResource resource) {
        return Math.max(1, ShopSeeding.suggestedPriceFor(level, plot, plotAnchor, resource));
    }
}
