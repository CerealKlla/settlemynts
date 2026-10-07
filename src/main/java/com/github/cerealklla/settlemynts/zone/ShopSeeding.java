package com.github.cerealklla.settlemynts.zone;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.github.cerealklla.settlemynts.bridge.BlueprintsConstructionBridge;
import com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModList;

/**
 * Drives {@link ShopSeedCatalog}s against a real plot's Shop (design doc Section 14a, added
 * 2026-10-05, explicit user request). Two entry points:
 *
 * <ul>
 *   <li>{@link #seedNewPlot} -- called once from {@code SettlemyntsMod#finalizePlot}, right after a
 *   plot's other auto-spawned fixtures (Plot Config Sign, Construction Box). Eagerly creates and
 *   seeds a Shop at Tier 1 if this Zone Type has a registered catalog -- not lazily on first "Manage
 *   Shop" the way a Shop with no catalog still works today.</li>
 *   <li>{@link #syncToCatalog} -- called from {@code SettlemyntsMod#requestShop} every time "Enter
 *   Shop"/"Manage Shop" opens. Re-resolves the plot's *current* Construction Box Tier and adds any
 *   catalog entries not yet listed (and tops up stock that's run low), additive-only -- never
 *   touches a listing's price once the owner (or a previous seed pass) has set one. This is what
 *   makes a Shop's offering "level up" automatically as the plot's own Tier rises, with no separate
 *   tier-change event needed.</li>
 * </ul>
 *
 * <p><b>Tier resolution</b>: a Construction Box's Blueprint (and so its real Tier) isn't chosen
 * until after Finalize, so {@link #seedNewPlot} always seeds at Tier 1 -- there's nothing higher to
 * seed yet. {@link #resolveTier} defaults to 1 whenever Blueprynts isn't loaded, the plot has no
 * Construction Box, or its Tier isn't resolvable for any other reason, so a plot's Shop is never
 * left with zero Tier-gated goods just because the Tier itself is momentarily unknown.
 */
public final class ShopSeeding {

    private ShopSeeding() {
    }

    public static void seedNewPlot(ServerLevel level, PlotRecord plot, GhostTownHallCoreEntity core, BlockPos plotAnchor) {
        if (!YconomicsShopBridge.isAvailable()) {
            return;
        }
        Optional<ShopSeedCatalog> catalog = ShopSeedCatalogRegistry.get(plot.zoneTypeId());
        if (catalog.isEmpty()) {
            return;
        }
        UUID shopId = YconomicsShopBridge.registerShop(level, plot.plotId());
        PlotRecord seeded = plot.withShopId(shopId);
        core.updatePlot(seeded);
        applyCatalog(level, seeded, shopId, catalog.get(), plotAnchor, 1);
    }

    public static void syncToCatalog(ServerLevel level, PlotRecord plot, GhostTownHallCoreEntity core, BlockPos plotAnchor) {
        if (!YconomicsShopBridge.isAvailable()) {
            return;
        }
        Optional<ShopSeedCatalog> catalog = ShopSeedCatalogRegistry.get(plot.zoneTypeId());
        if (catalog.isEmpty()) {
            return;
        }
        UUID shopId;
        if (plot.shopId().isPresent()) {
            shopId = plot.shopId().get();
        } else {
            shopId = YconomicsShopBridge.registerShop(level, plot.plotId());
            core.updatePlot(plot.withShopId(shopId));
        }
        applyCatalog(level, plot, shopId, catalog.get(), plotAnchor, resolveTier(level, plot));
    }

    private static final int NUGGET_FLOOR = 200;
    private static final ShopResource GOLD_NUGGETS = ShopResource.ofItem(BuiltInRegistries.ITEM.getKey(Items.GOLD_NUGGET));

    /**
     * Daily midnight restock (added 2026-10-05, explicit user request), called once per in-game day
     * boundary by {@code ShopMidnightRestockTicker} for every plot with a Shop, regardless of whether
     * anyone ever opens it. Two independent, unrelated top-ups:
     *
     * <ul>
     *   <li>Stack-backed catalog listings (Research/Recipe Notes -- the only current users of {@link
     *   SeedListing#ofStacks}) get topped back up toward the catalog's own seeded stock level, same
     *   top-up math {@link #applyCatalog} already uses for everything -- "small" falls out naturally
     *   here since a catalog only ever seeds a small handful of notes per entry. Plain physical goods
     *   (wood, food, etc.) are deliberately NOT restocked this way -- those are expected to be
     *   re-supplied by play (NPC auto-funding, an owner's own stock), not conjured daily.</li>
     *   <li>Every shop's own boxes get topped up to a flat {@value #NUGGET_FLOOR} Gold Nuggets if
     *   currently below that -- independent of any catalog, applies to every zone type with a Shop.</li>
     * </ul>
     */
    public static void restockAtMidnight(ServerLevel level, PlotRecord plot, BlockPos plotAnchor) {
        if (!YconomicsShopBridge.isAvailable() || plot.shopId().isEmpty()) {
            return;
        }
        List<Container> plotBoxes = com.github.cerealklla.settlemynts.api.Settlemynts.resolvePlotBoxes(level, plot.plotId());
        if (plotBoxes.isEmpty()) {
            return;
        }

        Optional<ShopSeedCatalog> catalog = ShopSeedCatalogRegistry.get(plot.zoneTypeId());
        if (catalog.isPresent()) {
            int tier = resolveTier(level, plot);
            for (SeedListing seed : catalog.get().seedListingsFor(level, plotAnchor, tier)) {
                if (seed.stacksToDeposit().isEmpty()) {
                    continue; // Only stack-backed (Research/Recipe Note) listings restock at midnight.
                }
                int currentStock = countStock(plotBoxes, seed.listingResource());
                int topUp = Math.max(0, seed.stockCount() - currentStock);
                if (topUp > 0) {
                    depositStock(plotBoxes, seed, topUp);
                }
            }
        }

        int currentNuggets = countStock(plotBoxes, GOLD_NUGGETS);
        int nuggetTopUp = Math.max(0, NUGGET_FLOOR - currentNuggets);
        if (nuggetTopUp > 0) {
            ContainerDeposit.depositIntoAny(plotBoxes, new ItemStack(Items.GOLD_NUGGET, nuggetTopUp));
        }
    }

    /**
     * Registers a catalog's listings (item + price) so an owner doesn't have to manually "Add
     * Listing (Held Item)" one at a time for every single good a shop of this Zone Type would
     * plausibly sell (explicit user request/clarification, 2026-10-06: "configure the shop...so a
     * player doesn't have to set up every single item for every shop"). <b>Never deposits any stock
     * for a resource-backed listing</b> -- a real correction the same day: an earlier pass had this
     * auto-filling boxes with a full stack of every listed good, which was never asked for ("I said
     * gold and research notes/recipes get restocked overnight, nothing else. The idea is that the
     * plot would have to buy from other plots/settlements if it needed such resources"). A listing
     * with no stock in the plot's boxes simply shows "Out of stock" until something -- an NPC
     * worker, the owner, another plot's trade -- actually puts real goods there. The one exception
     * is a stack-backed seed ({@link SeedListing#ofStacks}, Lyfe's Research/Recipe Notes) -- those
     * ARE deposited here (and re-topped-up nightly, see {@link #restockAtMidnight}), since a Note
     * has no other possible source: nothing else in the game can ever produce one.
     */
    private static void applyCatalog(ServerLevel level, PlotRecord plot, UUID shopId, ShopSeedCatalog catalog, BlockPos plotAnchor, int tier) {
        List<SeedListing> seedListings = catalog.seedListingsFor(level, plotAnchor, tier);
        if (seedListings.isEmpty()) {
            return;
        }
        List<YconomicsShopBridge.ShopListingView> existing = YconomicsShopBridge.getListings(level, shopId);
        List<Container> plotBoxes = null;
        for (SeedListing seed : seedListings) {
            ShopResource resource = seed.listingResource();
            boolean alreadyListed = existing.stream().anyMatch(l -> sameResource(l.resource(), resource));
            if (!alreadyListed) {
                YconomicsShopBridge.setListingPrice(level, shopId, resource, seed.pricePerUnit());
            }
            if (seed.stacksToDeposit().isEmpty()) {
                continue; // Resource-backed -- listing registered above, but never auto-stocked.
            }
            if (plotBoxes == null) {
                plotBoxes = com.github.cerealklla.settlemynts.api.Settlemynts.resolvePlotBoxes(level, plot.plotId());
            }
            if (plotBoxes.isEmpty()) {
                continue;
            }
            int currentStock = countStock(plotBoxes, resource);
            int topUp = Math.max(0, seed.stockCount() - currentStock);
            if (topUp <= 0) {
                continue;
            }
            depositStock(plotBoxes, seed, topUp);
        }
    }

    /** See {@code bridge.BlueprintsConstructionBridge#resolveTier}'s own doc for why 1 is the safe default. */
    private static int resolveTier(ServerLevel level, PlotRecord plot) {
        if (!ModList.get().isLoaded("blueprynts") || plot.constructionBoxId().isEmpty()) {
            return 1;
        }
        return BlueprintsConstructionBridge.resolveTier(level, plot.constructionBoxId().get()).orElse(1);
    }

    private static int countStock(List<Container> boxes, ShopResource resource) {
        int total = 0;
        for (Container box : boxes) {
            for (int slot = 0; slot < box.getContainerSize(); slot++) {
                ItemStack stack = box.getItem(slot);
                if (!stack.isEmpty() && resourceMatches(resource, stack)) {
                    total += stack.getCount();
                }
            }
        }
        return total;
    }

    private static void depositStock(List<Container> boxes, SeedListing seed, int amount) {
        List<ItemStack> toDeposit;
        if (!seed.stacksToDeposit().isEmpty()) {
            toDeposit = seed.stacksToDeposit().subList(0, Math.min(amount, seed.stacksToDeposit().size()));
        } else {
            ItemStack single = new ItemStack(BuiltInRegistries.ITEM.getValue(seed.resource().get().itemId()
                    .orElseThrow(() -> new IllegalStateException("Resource-backed SeedListing must be item-backed, not tag-backed"))), amount);
            toDeposit = List.of(single);
        }
        for (ItemStack stack : toDeposit) {
            ContainerDeposit.depositIntoAny(boxes, stack.copy());
        }
    }

    private static boolean resourceMatches(ShopResource resource, ItemStack stack) {
        if (resource.tag().isPresent()) {
            return stack.is(resource.tag().get());
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(resource.itemId().get());
    }

    private static boolean sameResource(ShopResource a, ShopResource b) {
        if (a.tag().isPresent() && b.tag().isPresent()) {
            return a.tag().get().equals(b.tag().get());
        }
        if (a.itemId().isPresent() && b.itemId().isPresent()) {
            return a.itemId().get().equals(b.itemId().get());
        }
        return false;
    }
}
