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
        // already draw for real listings themselves (see this method's own class doc). Skipped for a
        // tiered catalog (2026-10-10) -- applyTieredDefaults below fully supersedes this for those,
        // owner-agnostic, and running both would double up a resource's Planned Inventory target.
        if (seeded.owner().isEmpty() && !catalog.get().hasTierProgression()) {
            autoPopulatePlannedInventory(owner, seeded, catalog.get().seedListingsFor(level, plotAnchor, 1));
        }
        // Owner-agnostic default listings/Planned Inventory for a genuinely tiered catalog (Armorer/
        // Blacksmith/Restaurant), 2026-10-10 -- see applyTieredDefaults' own doc. A brand-new plot is
        // always Tier 1.
        applyTieredDefaults(level, owner, seeded, catalog.get(), plotAnchor, 1);
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
        // Owner-agnostic default seeding for a tiered catalog (2026-10-10) -- see applyTieredDefaults'
        // own doc. applyCatalogFull above already re-reads the plot fresh via its own owner.updatePlot
        // calls, but this call needs the latest Planned Inventory state, so re-resolve rather than
        // trust the now-possibly-stale `seeded` local.
        PlotRecord afterCatalog = owner.getPlots().stream().filter(p -> p.plotId().equals(plot.plotId())).findFirst().orElse(seeded);
        applyTieredDefaults(level, owner, afterCatalog, catalog.get(), plotAnchor, 1);
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
            int tier = resolveTier(level, plot);
            applyCatalogFull(level, owner, plot, catalog.get(), plotAnchor, tier);
            PlotRecord afterCatalog = owner.getPlots().stream().filter(p -> p.plotId().equals(plot.plotId())).findFirst().orElse(plot);
            applyTieredDefaults(level, owner, afterCatalog, catalog.get(), plotAnchor, tier);
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
            plot = plot.withShopId(shopId);
            owner.updatePlot(plot);
        }
        int tier = resolveTier(level, plot);
        applyCatalog(level, plot, catalog.get(), plotAnchor, tier);
        // Owner-agnostic default listings/Planned Inventory for a tiered catalog (2026-10-10) -- see
        // applyTieredDefaults' own doc. This is the main trigger point for a player-owned plot's own
        // Tier increase, since it runs every time Enter/Manage Shop opens.
        applyTieredDefaults(level, owner, plot, catalog.get(), plotAnchor, tier);
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
        // A tiered catalog (2026-10-10) leaves listing/Planned Inventory decisions entirely to
        // applyTieredDefaults (called right after this, at both call sites) -- "regardless of if a
        // player owns it or an NPC," the same curated previous-tier-full/current-tier-random split
        // applies, not this method's own "list literally everything" shape. Stock still gets
        // deposited either way -- nobody else would ever stock a natural shop's shelves otherwise.
        boolean tiered = catalog.hasTierProgression();
        List<Container> plotBoxes = com.github.cerealklla.settlemynts.api.Settlemynts.resolvePlotBoxes(level, plot.plotId());
        for (SeedListing seed : seedListings) {
            if (!tiered) {
                YconomicsShopBridge.setListingPrice(level, shopId, seed.listingResource(), seed.pricePerUnit());
            }
            if (plotBoxes.isEmpty()) {
                continue;
            }
            int currentStock = countStock(plotBoxes, seed.listingResource());
            int topUp = Math.max(0, seed.stockCount() - currentStock);
            if (topUp > 0) {
                depositStock(plotBoxes, seed, topUp);
            }
        }
        if (!tiered) {
            autoPopulatePlannedInventory(owner, plot, seedListings);
        }
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
     * <p><b>No longer also seeds crafting-material targets</b> (that piece added 2026-10-09, removed
     * again 2026-10-10) -- explicit user correction: "I don't intend any plots to be auto auto-seeded;
     * that's why we are slowly adding this logic in so they can ask for what they need from others...
     * I don't want any bifurcating logic between npc owned vs player owned in that sense. If there is
     * an NPC running the shop it needs to be able to support this concept" (a forward reference to a
     * future "hire an NPC to run your shop" feature, where an NPC-run shop should work exactly like a
     * player-run one). {@code zone.ShopWishlist#computeDeficits} now derives a sold item's material
     * need live from its recipe at ask-time instead (see that class's own doc) -- no persisted
     * material target, NPC-owned or player-owned alike, superseding what this used to pre-seed.
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
        }
        if (additions.isEmpty()) {
            return;
        }
        List<PlannedInventoryTarget> merged = new java.util.ArrayList<>(plot.plannedInventory());
        merged.addAll(additions);
        owner.updatePlot(plot.withPlannedInventory(merged));
    }

    /**
     * Default listing/Planned Inventory seeding for a catalog with real Tier progression
     * (Armorer/Blacksmith/Restaurant -- see {@link ShopSeedCatalog#hasTierProgression}'s own doc),
     * applied to **any** owner, player or NPC alike (2026-10-10, explicit user request: "This would
     * be the default configuration for any such plot, regardless of if a player owns it or an NPC" --
     * deliberately not restricted the way {@link #autoPopulatePlannedInventory}'s own NPC-only pass
     * is). No-op (and no write) unless {@code tier} is strictly higher than {@link
     * PlotRecord#tieredDefaultsAppliedTier()}, so a real Tier increase -- not every "Enter Shop" --
     * is what actually triggers a re-seed; also skipped entirely if this plot has no Shop yet.
     *
     * <ul>
     *   <li><b>Listings</b>: every not-yet-configured (no real listing, not explicitly suppressed)
     *   item from the Tier just below {@code tier} gets listed in full ("all items from their
     *   previous tier"); roughly half (rounded up, at least one if any exist), picked at random, of
     *   the items genuinely new at {@code tier} itself also get listed ("a random assortment of items
     *   from their current tier") -- the other half stays an unlisted catalog candidate row in Manage
     *   Shop, same as any other not-yet-decided item. Never overwrites an owner's (or an earlier pass's)
     *   explicit choice either way, same no-override convention every other seeding path here follows.</li>
     *   <li><b>Planned Inventory</b>: a target of exactly 1 for every item at {@code tier} and below
     *   ("1 of every item for their current tier and below"), additive-only -- never touches an
     *   existing target, whatever its count.</li>
     * </ul>
     *
     * <p>Only ever reads {@link SeedListing#resource()}-backed entries -- a stack-backed (Research/
     * Recipe Note) listing has no stable item identity to dedupe/compare by and is left entirely to
     * its own existing restock mechanism (see {@link #applyCatalog}/{@link #restockAtMidnight}).
     */
    public static void applyTieredDefaults(ServerLevel level, PlotOwner owner, PlotRecord plot, ShopSeedCatalog catalog, BlockPos plotAnchor, int tier) {
        if (!catalog.hasTierProgression() || plot.shopId().isEmpty() || tier <= plot.tieredDefaultsAppliedTier()) {
            return;
        }
        UUID shopId = plot.shopId().get();
        List<SeedListing> prevListings = tier > 1 ? catalog.seedListingsFor(level, plotAnchor, tier - 1) : List.of();
        List<SeedListing> allListings = catalog.seedListingsFor(level, plotAnchor, tier);

        java.util.Set<Identifier> prevItemIds = new java.util.HashSet<>();
        for (SeedListing seed : prevListings) {
            seed.resource().flatMap(ShopResource::itemId).ifPresent(prevItemIds::add);
        }

        java.util.Set<Identifier> alreadyConfigured = new java.util.HashSet<>();
        for (YconomicsShopBridge.ShopListingView view : YconomicsShopBridge.getListings(level, shopId)) {
            view.resource().itemId().ifPresent(alreadyConfigured::add);
        }
        for (SuppressedShopResource s : plot.suppressedShopResources()) {
            if (!s.isTag()) {
                alreadyConfigured.add(s.resourceKey());
            }
        }

        // 1. Every not-yet-configured item from the previous tier, listed in full.
        for (SeedListing seed : prevListings) {
            Optional<Identifier> itemId = seed.resource().flatMap(ShopResource::itemId);
            if (itemId.isEmpty() || alreadyConfigured.contains(itemId.get())) {
                continue;
            }
            YconomicsShopBridge.setListingPrice(level, shopId, seed.listingResource(), seed.pricePerUnit());
            alreadyConfigured.add(itemId.get());
        }

        // 2. About half (rounded up), randomly chosen, of the not-yet-configured items genuinely new
        // at this tier -- "a random assortment," not every single one, is the whole point here.
        List<SeedListing> eligibleNewThisTier = new java.util.ArrayList<>();
        for (SeedListing seed : allListings) {
            Optional<Identifier> itemId = seed.resource().flatMap(ShopResource::itemId);
            if (itemId.isEmpty() || prevItemIds.contains(itemId.get()) || alreadyConfigured.contains(itemId.get())) {
                continue;
            }
            eligibleNewThisTier.add(seed);
        }
        // Manual Fisher-Yates -- Collections.shuffle only accepts java.util.Random, not vanilla's own RandomSource.
        for (int i = eligibleNewThisTier.size() - 1; i > 0; i--) {
            int j = level.getRandom().nextInt(i + 1);
            java.util.Collections.swap(eligibleNewThisTier, i, j);
        }
        int pickCount = eligibleNewThisTier.isEmpty() ? 0 : Math.max(1, (eligibleNewThisTier.size() + 1) / 2);
        for (int i = 0; i < pickCount; i++) {
            SeedListing seed = eligibleNewThisTier.get(i);
            YconomicsShopBridge.setListingPrice(level, shopId, seed.listingResource(), seed.pricePerUnit());
        }

        // 3. Planned Inventory: 1 of every item at this tier and below, additive-only.
        java.util.Set<Identifier> existingTargets = new java.util.HashSet<>();
        for (PlannedInventoryTarget target : plot.plannedInventory()) {
            if (!target.isTag()) {
                existingTargets.add(target.resourceKey());
            }
        }
        List<PlannedInventoryTarget> additions = new java.util.ArrayList<>();
        for (SeedListing seed : allListings) {
            Optional<Identifier> itemId = seed.resource().flatMap(ShopResource::itemId);
            if (itemId.isEmpty() || !existingTargets.add(itemId.get())) {
                continue;
            }
            additions.add(new PlannedInventoryTarget(itemId.get(), false, 1));
        }

        List<PlannedInventoryTarget> mergedTargets = new java.util.ArrayList<>(plot.plannedInventory());
        mergedTargets.addAll(additions);
        owner.updatePlot(plot.withPlannedInventory(mergedTargets).withTieredDefaultsAppliedTier(tier));
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
