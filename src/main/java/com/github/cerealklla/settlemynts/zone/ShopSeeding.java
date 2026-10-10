package com.github.cerealklla.settlemynts.zone;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.github.cerealklla.settlemynts.bridge.BlueprintsConstructionBridge;
import com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
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

    public static void seedNewPlot(ServerLevel level, PlotRecord plot, PlotOwner owner, BlockPos plotAnchor) {
        if (!YconomicsShopBridge.isAvailable()) {
            return;
        }
        Optional<ShopSeedCatalog> catalog = ShopSeedCatalogRegistry.get(plot.zoneTypeId());
        if (catalog.isEmpty()) {
            return;
        }
        UUID shopId = YconomicsShopBridge.registerShop(level, plot.plotId());
        PlotRecord seeded = plot.withShopId(shopId);
        owner.updatePlot(seeded);
        applyCatalog(level, seeded, catalog.get(), plotAnchor, 1);
        // NPC-owned plot, 2026-10-09 (explicit follow-up request: "a first pass at automatically
        // setting the Planned Inventory for NPC shops with things that make sense") -- a player-owned
        // plot gets nothing auto-populated here, same owner/no-owner split as applyCatalog/applyCatalogFull
        // already draw for real listings themselves (see this method's own class doc).
        if (seeded.owner().isEmpty()) {
            autoPopulatePlannedInventory(owner, seeded, catalog.get().seedListingsFor(level, plotAnchor, 1));
        }
    }

    /** True if this Zone Type has a registered catalog at all -- lets a caller decide whether it's even worth resolving/creating a Shop before calling {@link #seedNewPlotSharingShop}. */
    public static boolean hasCatalog(Identifier zoneTypeId) {
        return ShopSeedCatalogRegistry.get(zoneTypeId).isPresent();
    }

    /**
     * Same as {@link #seedNewPlot}, except the plot is stamped with {@code sharedShopId} (an already-
     * resolved or freshly-minted Shop another plot of the same Zone Type may already be using) instead
     * of always minting a brand-new one -- see {@code zone.NaturalSettlementPlotStore}'s own
     * {@code sharedShops} doc for why natural-village plots of the same type all share one Shop.
     * Calling this repeatedly with the same {@code sharedShopId} for several plots is safe --
     * {@link #applyCatalogFull}'s stock top-up is idempotent either way.
     *
     * <p><b>Uses {@link #applyCatalogFull} (deposits + lists resource-backed goods too), not {@link
     * #applyCatalog}</b> -- a real bug found live 2026-10-08: natural-village Shops showed "no items
     * for sale" even after a midnight restock, because {@code applyCatalog}'s "never auto-list, the
     * owner must explicitly Save Changes" rule (added the same day, for an unrelated player-owned-plot
     * bug) silently applies here too, except a natural settlement's shared Shop has **no owner and no
     * Manage Shop access at all** -- nothing can ever perform that explicit save. The no-auto-list
     * rule is correct for a player-owned plot (an owner can and should curate their own listings); it
     * was never meant to apply to an NPC-owned shop with nobody able to curate it.
     */
    public static void seedNewPlotSharingShop(ServerLevel level, PlotRecord plot, PlotOwner owner, BlockPos plotAnchor, UUID sharedShopId) {
        if (!YconomicsShopBridge.isAvailable()) {
            return;
        }
        Optional<ShopSeedCatalog> catalog = ShopSeedCatalogRegistry.get(plot.zoneTypeId());
        if (catalog.isEmpty()) {
            return;
        }
        PlotRecord seeded = plot.withShopId(sharedShopId);
        owner.updatePlot(seeded);
        applyCatalogFull(level, owner, seeded, catalog.get(), plotAnchor, 1);
    }

    /**
     * Daily restock for a natural settlement's shared Shop -- the equivalent of {@link
     * #restockAtMidnight} for a Shop with no owner (see {@link #seedNewPlotSharingShop}'s own doc for
     * why that method, not {@link #applyCatalog}, is what this must also build on). Called from
     * {@code zone.ShopMidnightRestockTicker#restockNaturalSettlementShops} instead of the shared
     * {@link #restockAtMidnight} for the exact same reason: plain goods need to actually restock (and
     * stay listed) here, since no owner's own production ever supplies them.
     */
    public static void restockNaturalShop(ServerLevel level, PlotOwner owner, PlotRecord plot, BlockPos plotAnchor) {
        if (!YconomicsShopBridge.isAvailable() || plot.shopId().isEmpty()) {
            return;
        }
        Optional<ShopSeedCatalog> catalog = ShopSeedCatalogRegistry.get(plot.zoneTypeId());
        if (catalog.isPresent()) {
            applyCatalogFull(level, owner, plot, catalog.get(), plotAnchor, resolveTier(level, plot));
        }
        List<Container> plotBoxes = com.github.cerealklla.settlemynts.api.Settlemynts.resolvePlotBoxes(level, plot.plotId());
        if (plotBoxes.isEmpty()) {
            return;
        }
        int currentNuggets = countStock(plotBoxes, GOLD_NUGGETS);
        int nuggetTopUp = Math.max(0, NUGGET_FLOOR - currentNuggets);
        if (nuggetTopUp > 0) {
            ContainerDeposit.depositIntoAny(plotBoxes, new ItemStack(Items.GOLD_NUGGET, nuggetTopUp));
        }
    }

    public static void syncToCatalog(ServerLevel level, PlotRecord plot, PlotOwner owner, BlockPos plotAnchor) {
        if (!YconomicsShopBridge.isAvailable()) {
            return;
        }
        Optional<ShopSeedCatalog> catalog = ShopSeedCatalogRegistry.get(plot.zoneTypeId());
        if (catalog.isEmpty()) {
            return;
        }
        if (plot.shopId().isEmpty()) {
            UUID shopId = YconomicsShopBridge.registerShop(level, plot.plotId());
            owner.updatePlot(plot.withShopId(shopId));
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
     * The no-owner equivalent of {@link #applyCatalog} -- deposits stock AND creates/refreshes a real
     * listing for *every* catalog entry, resource-backed or stack-backed alike, since a natural
     * settlement's shared Shop has no owner to ever perform the explicit "Save Changes"
     * {@link #applyCatalog} now requires. Always (re)sets each listing's price to the catalog's own
     * value via {@code YconomicsShopBridge#setListingPrice} -- safe here specifically because nobody
     * can ever customize an NPC-owned shop's price away from the catalog default in the first place,
     * unlike the player-owned case {@link #applyCatalog} guards against overwriting.
     */
    private static void applyCatalogFull(ServerLevel level, PlotOwner owner, PlotRecord plot, ShopSeedCatalog catalog, BlockPos plotAnchor, int tier) {
        if (plot.shopId().isEmpty()) {
            return;
        }
        UUID shopId = plot.shopId().get();
        List<SeedListing> seedListings = catalog.seedListingsFor(level, plotAnchor, tier);
        if (seedListings.isEmpty()) {
            return;
        }
        List<Container> plotBoxes = com.github.cerealklla.settlemynts.api.Settlemynts.resolvePlotBoxes(level, plot.plotId());
        for (SeedListing seed : seedListings) {
            YconomicsShopBridge.setListingPrice(level, shopId, seed.listingResource(), seed.pricePerUnit());
            if (plotBoxes.isEmpty()) {
                continue;
            }
            int currentStock = countStock(plotBoxes, seed.listingResource());
            int topUp = Math.max(0, seed.stockCount() - currentStock);
            if (topUp > 0) {
                depositStock(plotBoxes, seed, topUp);
            }
        }
        autoPopulatePlannedInventory(owner, plot, seedListings);
    }

    /**
     * First-pass automatic Planned Inventory for an NPC-owned shop (2026-10-09, explicit follow-up
     * request: "a first pass at automatically setting the Planned Inventory for NPC shops with things
     * that make sense") -- reuses each {@link SeedListing#stockCount()} as the resource's default
     * "happy state" target, the catalog's own idea of a reasonable baseline stock level. Only ever
     * *adds* a target for a resource with no existing entry (same additive-only, idempotent shape as
     * this class's own stock top-ups) -- never overwrites one a Town Planner has since hand-edited via
     * {@code client.PlannedInventoryScreen}. A player-owned plot never reaches this method at all (see
     * both call sites' own guards) -- the owner decides their own happy state manually, same as they
     * decide their own prices via Manage Shop.
     *
     * <p><b>Also seeds crafting-material targets, same day, explicit follow-up confirmation</b>: for
     * any catalog item that's a known {@code bridge.LyfeCraftingBridge} recipe output (e.g. a
     * Blacksmith's Tier-appropriate weapons/armor), also adds a target for each required material --
     * each {@code specificComponents} entry at {@code seed.stockCount() * qty}, and for each
     * {@code genericComponents} group its first/declaration-order member (a simple starting default;
     * the midnight engine's own cheapest-member logic in {@code PlannedInventoryClearing} is what
     * actually matters at real trade time) at {@code seed.stockCount() * qty} -- so a fresh NPC
     * Blacksmith isn't stuck with a forge and nothing to feed it.
     */
    private static void autoPopulatePlannedInventory(PlotOwner owner, PlotRecord plot, List<SeedListing> seedListings) {
        java.util.Set<java.util.Map.Entry<Identifier, Boolean>> existing = new java.util.HashSet<>();
        for (PlannedInventoryTarget target : plot.plannedInventory()) {
            existing.add(java.util.Map.entry(target.resourceKey(), target.isTag()));
        }
        List<PlannedInventoryTarget> additions = new java.util.ArrayList<>();
        for (SeedListing seed : seedListings) {
            ShopResource resource = seed.listingResource();
            Identifier key = resource.tag().isPresent() ? resource.tag().get().location() : resource.itemId().get();
            boolean isTag = resource.tag().isPresent();
            if (existing.add(java.util.Map.entry(key, isTag))) {
                additions.add(new PlannedInventoryTarget(key, isTag, seed.stockCount()));
            }
            if (!isTag && com.github.cerealklla.settlemynts.bridge.LyfeCraftingBridge.isLoaded()) {
                com.github.cerealklla.settlemynts.bridge.LyfeCraftingBridge.getRecipe(key).ifPresent(recipe -> {
                    for (java.util.Map.Entry<Identifier, Integer> component : recipe.specificComponents().entrySet()) {
                        if (existing.add(java.util.Map.entry(component.getKey(), false))) {
                            additions.add(new PlannedInventoryTarget(component.getKey(), false, seed.stockCount() * component.getValue()));
                        }
                    }
                    for (java.util.Map.Entry<String, Integer> group : recipe.genericComponents().entrySet()) {
                        List<Identifier> members = com.github.cerealklla.settlemynts.bridge.LyfeCraftingBridge.componentGroupMembers(group.getKey());
                        if (members.isEmpty()) {
                            continue;
                        }
                        Identifier representative = members.get(0);
                        if (existing.add(java.util.Map.entry(representative, false))) {
                            additions.add(new PlannedInventoryTarget(representative, false, seed.stockCount() * group.getValue()));
                        }
                    }
                });
            }
        }
        if (additions.isEmpty()) {
            return;
        }
        List<PlannedInventoryTarget> merged = new java.util.ArrayList<>(plot.plannedInventory());
        merged.addAll(additions);
        owner.updatePlot(plot.withPlannedInventory(merged));
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
     * The suggested price for one exact crafted-quality variant of {@code plainResource}'s item
     * (2026-10-10, per-quality Shop listings) -- the plain item's own catalog suggestion ({@link
     * #suggestedPriceFor}) plus 1 gold per whole icon of quality baked into {@code sample} (explicit
     * user formula: "base + 1 per [#]... a 2.75 would be 2 + 2"), i.e. {@code base + floor(icons)}.
     * Falls back to the plain suggestion unchanged if Lyfe isn't loaded or {@code sample} isn't a
     * Lyfe crafted-food item at all.
     */
    public static int suggestedPriceForVariant(ServerLevel level, PlotRecord plot, BlockPos plotAnchor, ShopResource plainResource, ItemStack sample) {
        int base = suggestedPriceFor(level, plot, plotAnchor, plainResource);
        if (!com.github.cerealklla.settlemynts.bridge.LyfeCookingBridge.isLoaded()) {
            return base;
        }
        return com.github.cerealklla.settlemynts.bridge.LyfeCookingBridge.getIcons(sample)
                .map(icons -> base + (int) Math.floor(icons))
                .orElse(base);
    }

    /**
     * Auto-lists every Lyfe crafted-food quality variant physically sitting in an NPC-owned (no
     * player owner) plot's boxes that isn't already listed or explicitly suppressed (2026-10-10,
     * explicit follow-up request: "that way npc villages could automatically list food separated by
     * value") -- the no-curator-needed counterpart to {@code client.ManageShopScreen}'s own
     * per-quality rows, for an NPC-owned plot nobody will necessarily ever open Manage Shop for.
     * Price is the same base+floor(icons) formula that screen shows as a pure suggestion ({@code
     * suggestedPriceFor} plus one gold per whole icon of quality); the real stored listing still
     * floor-clamps to the shop system's own minimum regardless. Respects {@code
     * plot.suppressedShopResources()} same as every other auto-listing path here -- a variant the
     * plot's own Mayor/Town Planners have explicitly blanked via Manage Shop is never relisted.
     * Called once per midnight settlement pass, right after {@code
     * zone.PlannedInventoryClearing#craftFromOwnStockForGroup} produces whatever it's going to
     * produce that night -- see {@code ShopMidnightRestockTicker}'s own call site.
     */
    public static void autoListCraftedFoodVariants(ServerLevel level, PlotRecord plot, BlockPos plotAnchor) {
        if (!YconomicsShopBridge.isAvailable() || plot.shopId().isEmpty()
                || !com.github.cerealklla.settlemynts.bridge.LyfeCookingBridge.isLoaded()) {
            return;
        }
        List<Container> boxes = com.github.cerealklla.settlemynts.api.Settlemynts.resolvePlotBoxes(level, plot.plotId());
        if (boxes.isEmpty()) {
            return;
        }
        UUID shopId = plot.shopId().get();
        java.util.Set<ShopResource> alreadyListed = YconomicsShopBridge.getListings(level, shopId).stream()
                .map(YconomicsShopBridge.ShopListingView::resource)
                .collect(java.util.stream.Collectors.toSet());
        java.util.Set<ShopResource> suppressed = plot.suppressedShopResources().stream()
                .filter(s -> !s.isTag())
                .map(s -> s.customName().isPresent()
                        ? ShopResource.ofExactItem(s.resourceKey(), s.customName().get())
                        : ShopResource.ofItem(s.resourceKey()))
                .collect(java.util.stream.Collectors.toSet());
        for (java.util.Map.Entry<ShopResource, com.github.cerealklla.settlemynts.api.Settlemynts.VariantStock> entry :
                com.github.cerealklla.settlemynts.api.Settlemynts.scanItemStockByVariant(boxes).entrySet()) {
            ShopResource variant = entry.getKey();
            if (variant.customName().isEmpty() || alreadyListed.contains(variant) || suppressed.contains(variant)) {
                continue;
            }
            ShopResource plain = ShopResource.ofItem(variant.itemId().get());
            int price = suggestedPriceForVariant(level, plot, plotAnchor, plain, entry.getValue().sample());
            YconomicsShopBridge.setListingPrice(level, shopId, variant, price);
        }
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
