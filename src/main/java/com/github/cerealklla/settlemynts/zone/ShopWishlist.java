package com.github.cerealklla.settlemynts.zone;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;

import com.github.cerealklla.settlemynts.api.Settlemynts;
import com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge;

/**
 * A shop's public "what do I still need" list (2026-10-10, explicit user request: "I'd like shops to
 * have a public wishlist, which shows what the shop needs in order to accomplish its preferred
 * inventory"). Deliberately read-only/side-effect-free -- unlike the midnight engine, this never
 * moves any items or gold itself.
 *
 * <p><b>Only crafting inputs, never the shop's own finished-good stock targets</b> (corrected
 * 2026-10-10, real feedback: "this should be the list of things the shop would buy from other shops
 * in order to craft the things it wants to sell. This should NOT list things that the shop wants to
 * sell, otherwise they'd just be buying their stock at 10% higher than their likely sale price" --
 * e.g. the Armorer should wishlist raw Ingots, never the finished Armor it already has for sale). A
 * {@link PlannedInventoryTarget} alone doesn't distinguish the two -- {@code
 * ShopSeeding#autoPopulatePlannedInventory} seeds a target for both a catalog item itself (a
 * finished good, always backed by a real Shop listing too) and its own crafting materials (never
 * listed for sale). So the real distinguishing signal already present in this plot's own data is
 * exactly that: a deficit resource with a real Shop listing is something the shop sells (excluded);
 * one with no listing at all is something it only ever buys (a genuine wishlist candidate).
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

    /**
     * Every {@link PlannedInventoryTarget} this plot is currently below AND doesn't itself sell (see
     * class doc), with how many units short. Empty if the plot has no Planned Inventory at all, or
     * nothing qualifying is currently short.
     */
    public static List<DeficitEntry> computeDeficits(ServerLevel level, PlotRecord plot) {
        List<DeficitEntry> deficits = new ArrayList<>();
        if (plot.plannedInventory().isEmpty()) {
            return deficits;
        }
        List<Container> boxes = Settlemynts.resolvePlotBoxes(level, plot.plotId());
        Set<Identifier> soldItemIds = plot.shopId()
                .map(shopId -> YconomicsShopBridge.getListings(level, shopId).stream()
                        .map(view -> view.resource().itemId())
                        .filter(java.util.Optional::isPresent)
                        .map(java.util.Optional::get)
                        .collect(Collectors.toSet()))
                .orElse(Set.of());
        for (PlannedInventoryTarget target : plot.plannedInventory()) {
            if (!target.isTag() && soldItemIds.contains(target.resourceKey())) {
                continue; // The shop sells this itself -- restocking its own merchandise isn't a wishlist ask.
            }
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
