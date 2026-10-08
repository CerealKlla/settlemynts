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
        applyCatalog(level, seeded, catalog.get(), plotAnchor, 1);
    }

    public static void syncToCatalog(ServerLevel level, PlotRecord plot, GhostTownHallCoreEntity core, BlockPos plotAnchor) {
        if (!YconomicsShopBridge.isAvailable()) {
            return;
        }
        Optional<ShopSeedCatalog> catalog = ShopSeedCatalogRegistry.get(plot.zoneTypeId());
        if (catalog.isEmpty()) {
            return;
        }
        if (plot.shopId().isEmpty()) {
            UUID shopId = YconomicsShopBridge.registerShop(level, plot.plotId());
            core.updatePlot(plot.withShopId(shopId));
        }
        applyCatalog(level, plot, catalog.get(), plotAnchor, resolveTier(level, plot));
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
     * Tops up stock-backed catalog goods (Lyfe's Research/Recipe Notes) -- the only ones with no
     * other possible source in the game. <b>No longer auto-creates or auto-prices any listing</b>
     * (changed 2026-10-08, real bug report: a Farm plot's catalog-seeded crops kept reappearing in
     * the real Shop every time Manage Shop reopened, even after the owner explicitly blanked their
     * Sell Cost and saved -- this ran additively on every open with no way to tell "never seeded"
     * apart from "owner removed this on purpose"). The fix the user actually asked for: "just don't
     * set default prices on items that currently have no cost. That way the first time the player
     * can exclude them, and every time after that it'll respect that." A listing is now created
     * *only* by an explicit {@code client.ManageShopScreen} "Save Changes" -- see {@link
     * #suggestedPriceFor} for how that screen still shows a catalog-appropriate starting price as a
     * pure UI suggestion, never auto-applied. Resource-backed seeds (plain goods) never deposit
     * stock either way -- see {@link #suggestedPriceFor}'s sibling doc below for the stock-vs-listing
     * split this class has kept since 2026-10-06.
     */
    private static void applyCatalog(ServerLevel level, PlotRecord plot, ShopSeedCatalog catalog, BlockPos plotAnchor, int tier) {
        List<SeedListing> seedListings = catalog.seedListingsFor(level, plotAnchor, tier);
        if (seedListings.isEmpty()) {
            return;
        }
        List<Container> plotBoxes = null;
        for (SeedListing seed : seedListings) {
            if (seed.stacksToDeposit().isEmpty()) {
                continue; // Resource-backed -- no listing, no stock; see this method's own class doc.
            }
            if (plotBoxes == null) {
                plotBoxes = com.github.cerealklla.settlemynts.api.Settlemynts.resolvePlotBoxes(level, plot.plotId());
            }
            if (plotBoxes.isEmpty()) {
                continue;
            }
            int currentStock = countStock(plotBoxes, seed.listingResource());
            int topUp = Math.max(0, seed.stockCount() - currentStock);
            if (topUp <= 0) {
                continue;
            }
            depositStock(plotBoxes, seed, topUp);
        }
    }

    /**
     * The catalog's recommended starting price for {@code resource}, or {@code 0} if this Zone Type
     * has no catalog or no entry for it -- a pure UI suggestion for {@code
     * client.ManageShopScreen}'s Sell Cost box, used only to pre-fill a row that has no real listing
     * yet. Never applied automatically; the row only becomes a real listing if the owner leaves (or
     * types) a price and clicks "Save Changes" -- see {@link #applyCatalog}'s own doc for why this
     * replaced the old always-on auto-listing behavior.
     */
    public static int suggestedPriceFor(ServerLevel level, PlotRecord plot, BlockPos plotAnchor, ShopResource resource) {
        for (SeedListing seed : catalogSeedListings(level, plot, plotAnchor)) {
            if (sameResource(seed.listingResource(), resource)) {
                return seed.pricePerUnit();
            }
        }
        return 0;
    }

    /**
     * Every {@link SeedListing} this plot's Zone Type's catalog recommends at its current Tier, or
     * an empty list if it has none -- the full candidate set {@code client.ManageShopScreen} shows a
     * row for even with zero box stock and no real listing (added 2026-10-08, real follow-up report:
     * after explicitly un-listing a catalog crop at zero stock, its row vanished from Manage Shop
     * entirely with no way to re-list it short of physically restocking it first). A catalog
     * recommendation is always shown as a candidate row -- see {@code SettlemyntsMod#requestShop}'s
     * manage-mode row assembly for how this merges with real listings/live box stock.
     */
    public static List<SeedListing> catalogSeedListings(ServerLevel level, PlotRecord plot, BlockPos plotAnchor) {
        Optional<ShopSeedCatalog> catalog = ShopSeedCatalogRegistry.get(plot.zoneTypeId());
        if (catalog.isEmpty()) {
            return List.of();
        }
        return catalog.get().seedListingsFor(level, plotAnchor, resolveTier(level, plot));
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
