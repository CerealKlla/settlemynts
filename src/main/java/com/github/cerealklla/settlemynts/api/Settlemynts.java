package com.github.cerealklla.settlemynts.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import com.github.cerealklla.cartographyr.api.Cartography;
import com.github.cerealklla.cartographyr.geo.EntityId;
import com.github.cerealklla.cartographyr.geo.GeographicEntity;
import com.github.cerealklla.cartographyr.geo.Geometry;
import com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.registration.ModEntities;
import com.github.cerealklla.settlemynts.zone.ContainerDeposit;
import com.github.cerealklla.settlemynts.zone.ContainerWithdraw;
import com.github.cerealklla.settlemynts.zone.PlotGeometry;
import com.github.cerealklla.settlemynts.zone.PlotPermissions;
import com.github.cerealklla.settlemynts.zone.PlotRecord;
import com.github.cerealklla.settlemynts.zone.ShopResource;
import com.github.cerealklla.settlemynts.zone.ShopSeedCatalog;
import com.github.cerealklla.settlemynts.zone.ShopSeedCatalogRegistry;
import com.github.cerealklla.settlemynts.zone.ZoneType;
import com.github.cerealklla.settlemynts.zone.ZoneTypeRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.neoforged.fml.ModList;

/**
 * The stable public entry point for other mods to integrate with Settlemynts -- the open plot
 * {@link ZoneType} registry (design doc Section 11a), plus, as of 2026-10-05, read access to plots/
 * settlements and the real Shop/pricing system (design doc Section 14a) built this session for the
 * Lyfe crafting-structure upgrade-cost feature. Same "stable facade, don't reach into internals"
 * pattern as Cartographyr's {@code Cartography}, Lyfe's {@code api.Lyfe}, and Yconomics' {@code
 * api.Yconomics}.
 *
 * <p>Settlemynts itself only ships two built-in zone types ("Town Hall", "Private Residence") --
 * a future Blueprynts mod (and others) is expected to register the rest via {@link
 * #registerZoneType}, e.g. commercial/industrial/farm zones tied to whatever it builds.
 *
 * <p><b>Every Shop method here is a no-op/empty-returning no-op if Yconomics isn't loaded</b> (see
 * {@code bridge.YconomicsShopBridge#isAvailable}) -- callers don't need their own
 * {@code ModList.isLoaded("yconomics")} check before calling these.
 */
public final class Settlemynts {

    // Same large-AABB "it's never removed, only (rarely) moved" scan shape as
    // founding.TownHallCoreRelocatorItem#findCore -- no chunk-independent settlement index exists.
    private static final double WORLD_SCAN_RADIUS = 3.0E7;

    private Settlemynts() {
    }

    /** Registers a {@link ZoneType}, no MinecraftServer/ServerLevel param -- same startup-time, in-memory-registry shape as Cartographyr's {@code registerLayer}/{@code registerProtectionLevel}. */
    public static void registerZoneType(ZoneType type) {
        ZoneTypeRegistry.register(type);
    }

    public static Optional<ZoneType> getZoneType(Identifier id) {
        return ZoneTypeRegistry.get(id);
    }

    public static Collection<ZoneType> getRegisteredZoneTypes() {
        return ZoneTypeRegistry.all();
    }

    /** Registers a {@link ShopSeedCatalog} for a Zone Type (design doc Section 14a, 2026-10-05) -- see {@code zone.ShopSeeding}'s own doc for when/how this gets used. Same open, replace-on-reregister governance as {@link #registerZoneType}. */
    public static void registerShopSeedCatalog(Identifier zoneTypeId, ShopSeedCatalog catalog) {
        ShopSeedCatalogRegistry.register(zoneTypeId, catalog);
    }

    public static Optional<ShopSeedCatalog> getShopSeedCatalog(Identifier zoneTypeId) {
        return ShopSeedCatalogRegistry.get(zoneTypeId);
    }

    /**
     * A finalized plot's public-facing details -- everything a cross-mod caller needs without
     * reaching into {@code zone.PlotRecord}/{@code founding.GhostTownHallCoreEntity} directly.
     * {@code tier} (added 2026-10-09 for Lyfe's crafting/cooking structure upgrade cap -- "do not
     * allow the structure to upgrade past the limit of the plot itself") is the plot's own unlocked
     * construction-Tier cap, see {@code zone.PlotRecord#tier}'s own class doc for the full
     * plot-tier-vs-building-tier split this mirrors.
     */
    public record PlotHandle(UUID plotId, Identifier zoneTypeId, Optional<UUID> owner, UUID settlementCoreId, Geometry.Polygon polygon, int tier) {
    }

    /**
     * Can {@code playerId} manage the finalized plot at {@code pos} -- a thin cross-mod wrapper over
     * {@code zone.PlotPermissions#canManage}, added 2026-10-09 for Lyfe's crafting/cooking structure
     * upgrade button (real report: "do not show the upgrade button" to a non-owner). {@code false} if
     * there's no plot there at all, same as every other negative case here.
     */
    public static boolean canManagePlotAt(ServerLevel level, BlockPos pos, UUID playerId) {
        AABB worldBounds = new AABB(-WORLD_SCAN_RADIUS, level.getMinY(), -WORLD_SCAN_RADIUS,
                WORLD_SCAN_RADIUS, level.getMaxY(), WORLD_SCAN_RADIUS);
        int coreCount = 0;
        for (GhostTownHallCoreEntity core : level.getEntities(ModEntities.GHOST_TOWN_HALL_CORE.get(), worldBounds, GhostTownHallCoreEntity::isFinalized)) {
            coreCount++;
            Optional<PlotRecord> plot = PlotGeometry.findContainingPlot(level, core, pos.getX(), pos.getZ());
            if (plot.isPresent()) {
                boolean result = PlotPermissions.canManage(plot.get(), core, playerId);
                com.github.cerealklla.settlemynts.SettlemyntsMod.LOGGER.info(
                        "[CanManageDebug] canManagePlotAt pos={} player={} -> found plot {} on core '{}' (founder={}) -> canManage={}",
                        pos, playerId, plot.get().plotId(), core.getSettlementName(), core.getFounderId(), result);
                return result;
            }
        }
        com.github.cerealklla.settlemynts.SettlemyntsMod.LOGGER.info(
                "[CanManageDebug] canManagePlotAt pos={} player={} -> NO containing plot found among {} finalized core(s)",
                pos, playerId, coreCount);
        return false;
    }

    /**
     * The finalized plot (if any) containing {@code pos} -- added 2026-10-05 for Lyfe's
     * crafting-structure upgrade-cost feature (resolving "which plot is this structure on"), the
     * first public API to answer that question; everything prior to this was internal-only ({@code
     * zone.PlotGeometry#findContainingPlot}). Scans every loaded, finalized settlement core in the
     * dimension -- same accepted "loaded chunks only" limitation {@code
     * construction.NpcAutoFundingTicker} already has.
     */
    public static Optional<PlotHandle> findPlotAt(ServerLevel level, BlockPos pos) {
        AABB worldBounds = new AABB(-WORLD_SCAN_RADIUS, level.getMinY(), -WORLD_SCAN_RADIUS,
                WORLD_SCAN_RADIUS, level.getMaxY(), WORLD_SCAN_RADIUS);
        for (GhostTownHallCoreEntity core : level.getEntities(ModEntities.GHOST_TOWN_HALL_CORE.get(), worldBounds, GhostTownHallCoreEntity::isFinalized)) {
            Optional<PlotRecord> plot = PlotGeometry.findContainingPlot(level, core, pos.getX(), pos.getZ());
            if (plot.isPresent()) {
                Optional<Geometry.Polygon> polygon = resolvePolygon(level, plot.get());
                if (polygon.isPresent()) {
                    return Optional.of(new PlotHandle(plot.get().plotId(), plot.get().zoneTypeId(), plot.get().owner(), core.getUUID(), polygon.get(), plot.get().tier()));
                }
            }
        }
        return findNaturalPlotAt(level, pos);
    }

    /**
     * Natural (NPC-generated) village plots are never attached to a real {@link
     * GhostTownHallCoreEntity} at all -- they live in {@code zone.NaturalSettlementPlotStore}
     * instead (see that class's own doc) -- so the loop above never finds them, regardless of how
     * similar a natural village "looks" in-game. Added 2026-10-09, real gap found via Lyfe's
     * Recallcinite Totem feature: a player standing in a real natural-village "Residence" plot
     * resolved no plot at all through {@link #findPlotAt}, even though {@code
     * location.LocationTracker}'s own HUD (a separate, Cartographyr-direct lookup) correctly showed
     * "Residence". A linear scan over every tracked natural settlement's plots, resolving each
     * one's polygon the same way the regular path does -- same "whole-store scan, fine at this
     * suite's scale" precedent {@code NaturalSettlementPlotStore#findByPlotId} already uses.
     *
     * <p>{@code settlementCoreId} has no real core entity to report for a natural village, so a
     * stable id is derived deterministically from its {@code SettlementKey} instead -- unique and
     * consistent across calls/sessions for the same settlement, which is all callers actually need
     * it for (grouping/dedup keys, never an actual entity lookup).
     */
    private static Optional<PlotHandle> findNaturalPlotAt(ServerLevel level, BlockPos pos) {
        var store = com.github.cerealklla.settlemynts.zone.NaturalSettlementPlotStore.get(level.getServer());
        for (var entry : store.all().entrySet()) {
            com.github.cerealklla.settlemynts.zone.SettlementKey settlement = entry.getKey();
            for (PlotRecord plot : entry.getValue()) {
                Optional<Geometry.Polygon> polygon = resolvePolygon(level, plot);
                if (polygon.isPresent() && polygon.get().contains(pos.getX(), pos.getZ())) {
                    UUID settlementCoreId = new UUID(settlement.worldSeed(), settlement.settlementEntityId());
                    return Optional.of(new PlotHandle(plot.plotId(), plot.zoneTypeId(), plot.owner(), settlementCoreId, polygon.get(), plot.tier()));
                }
            }
        }
        return Optional.empty();
    }

    /** A nearby settlement's core id and distance -- see {@link #findNearbySettlements}. */
    public record SettlementHandle(UUID settlementCoreId, double distanceBlocks) {
    }

    /**
     * Every finalized settlement within {@code radiusBlocks} of {@code origin}, nearest first --
     * generalizes {@code founding.SettlementFounding#findNearestSettlement}'s single-result
     * distance+direction check into a full list, needed for "find the nearest settlement that sells
     * X" (added 2026-10-05, same Shop feature as {@link #findPlotAt}). Same loaded-chunks-only scan
     * as that method.
     */
    public static List<SettlementHandle> findNearbySettlements(ServerLevel level, BlockPos origin, double radiusBlocks) {
        AABB searchBox = new AABB(
                origin.getX() - radiusBlocks, level.getMinY(), origin.getZ() - radiusBlocks,
                origin.getX() + radiusBlocks, level.getMaxY(), origin.getZ() + radiusBlocks);
        List<SettlementHandle> result = new ArrayList<>();
        for (GhostTownHallCoreEntity core : level.getEntities(ModEntities.GHOST_TOWN_HALL_CORE.get(), searchBox, GhostTownHallCoreEntity::isFinalized)) {
            double distance = Math.sqrt(core.distanceToSqr(origin.getX() + 0.5, core.getY(), origin.getZ() + 0.5));
            if (distance <= radiusBlocks) {
                result.add(new SettlementHandle(core.getUUID(), distance));
            }
        }
        result.sort(Comparator.comparingDouble(SettlementHandle::distanceBlocks));
        return result;
    }

    /** A plot's current Shop listings, if it has a Shop at all -- empty if it doesn't, or Yconomics isn't loaded. */
    public static List<YconomicsShopBridge.ShopListingView> getListings(ServerLevel level, UUID plotId) {
        if (!YconomicsShopBridge.isAvailable()) {
            return List.of();
        }
        return YconomicsShopBridge.getShopIdFor(level, plotId)
                .map(shopId -> YconomicsShopBridge.getListings(level, shopId))
                .orElse(List.of());
    }

    /**
     * The average listed sale price for {@code resource} across every plot in {@code
     * settlementCoreId}'s own settlement that currently sells it -- empty if nobody local does
     * (the caller's cue to fall back to {@link #findNearbySettlements}), or if the settlement/
     * Yconomics isn't available. Resource matching is by {@link ShopResource#itemId()}/{@link
     * ShopResource#tag()} identity (tag-vs-tag or item-vs-item, no cross-matching), same equality
     * Yconomics' own {@code shop.ShopResource#key()} uses internally.
     */
    public static OptionalInt getAverageSettlementPrice(ServerLevel level, UUID settlementCoreId, ShopResource resource) {
        if (!YconomicsShopBridge.isAvailable() || !(level.getEntity(settlementCoreId) instanceof GhostTownHallCoreEntity core)) {
            return OptionalInt.empty();
        }
        List<Integer> prices = new ArrayList<>();
        for (PlotRecord plot : core.getPlots()) {
            for (YconomicsShopBridge.ShopListingView listing : getListings(level, plot.plotId())) {
                if (sameResource(listing.resource(), resource)) {
                    prices.add(listing.pricePerUnit());
                }
            }
        }
        if (prices.isEmpty()) {
            return OptionalInt.empty();
        }
        return OptionalInt.of((int) Math.round(prices.stream().mapToInt(Integer::intValue).average().orElse(0)));
    }

    /** The first plot (if any) in {@code settlementCoreId}'s settlement whose Shop currently lists {@code resource} -- for a caller that needs to actually buy from a specific shop, not just read a price. No stock check (a listing with zero stock still matches) -- the caller's own purchase call will simply fill 0. */
    public static Optional<UUID> findSellingPlot(ServerLevel level, UUID settlementCoreId, ShopResource resource) {
        if (!YconomicsShopBridge.isAvailable() || !(level.getEntity(settlementCoreId) instanceof GhostTownHallCoreEntity core)) {
            return Optional.empty();
        }
        for (PlotRecord plot : core.getPlots()) {
            for (YconomicsShopBridge.ShopListingView listing : getListings(level, plot.plotId())) {
                if (sameResource(listing.resource(), resource)) {
                    return Optional.of(plot.plotId());
                }
            }
        }
        return Optional.empty();
    }

    /** One plot's own listed price for a resource -- see {@link #findSellingPlots}. */
    public record PlotPrice(UUID plotId, int pricePerUnit) {
    }

    /**
     * Every plot (if any) in {@code settlementCoreId}'s settlement whose Shop currently lists {@code
     * resource}, each with its own listed price -- added 2026-10-09 for {@code
     * construction.PlotTierMarketPurchasing}'s cheapest-first, stock-aware multi-seller allocation
     * (explicit request: "using the cheapest of all plot store prices within the settlement, and
     * keeping in mind available stock at those stores" -- {@link #findSellingPlot}/{@link
     * #getAverageSettlementPrice} alone can't answer that, since they only ever return one plot or
     * one averaged number). No stock check here either, same reasoning as {@link #findSellingPlot}.
     */
    public static List<PlotPrice> findSellingPlots(ServerLevel level, UUID settlementCoreId, ShopResource resource) {
        List<PlotPrice> result = new ArrayList<>();
        if (!YconomicsShopBridge.isAvailable() || !(level.getEntity(settlementCoreId) instanceof GhostTownHallCoreEntity core)) {
            return result;
        }
        for (PlotRecord plot : core.getPlots()) {
            for (YconomicsShopBridge.ShopListingView listing : getListings(level, plot.plotId())) {
                if (sameResource(listing.resource(), resource)) {
                    result.add(new PlotPrice(plot.plotId(), listing.pricePerUnit()));
                }
            }
        }
        return result;
    }

    /**
     * Every real shop listing across every plot in {@code settlementCoreId}'s settlement, unfiltered
     * by resource -- added 2026-10-11 for Myssions' "buy an item from a local shop" quest, which
     * needs to ask "does this settlement sell anything at all" rather than "does it sell this one
     * specific thing" ({@link #findSellingPlot}/{@link #findSellingPlots}). Same loop, just not
     * filtered to one resource, and the same existing limitation: player-founded settlements only
     * (resolved via {@link GhostTownHallCoreEntity#getPlots()}), not natural villages.
     */
    public static List<YconomicsShopBridge.ShopListingView> getAllSettlementListings(ServerLevel level, UUID settlementCoreId) {
        List<YconomicsShopBridge.ShopListingView> result = new ArrayList<>();
        if (!YconomicsShopBridge.isAvailable() || !(level.getEntity(settlementCoreId) instanceof GhostTownHallCoreEntity core)) {
            return result;
        }
        for (PlotRecord plot : core.getPlots()) {
            result.addAll(getListings(level, plot.plotId()));
        }
        return result;
    }

    /**
     * Same as {@link #getAllSettlementListings}, but filtered down to listings whose vendor plot
     * actually has at least 1 unit of the concrete item physically sitting in its boxes right now --
     * added 2026-10-12 for Myssions' "A Useful Errand" quest, which picked its target purely from
     * {@link #getAllSettlementListings} (listed for sale, regardless of stock) and could hand a
     * player an item the vendor had no real stock of at all. Tag-based listings are skipped (same as
     * every existing caller on the Myssions side -- {@code bridge.SettlemyntsShopBridge} only ever
     * resolves a listing with a concrete {@code itemId} to begin with, since a tag doesn't name one
     * item to hand over). Deliberately a new method rather than changing {@link #getAllSettlementListings}
     * itself -- that method's existing callers ({@link #findSellingPlots} included) intentionally don't
     * stock-check, per their own doc.
     */
    public static List<YconomicsShopBridge.ShopListingView> getAllSettlementListingsInStock(ServerLevel level, UUID settlementCoreId) {
        List<YconomicsShopBridge.ShopListingView> result = new ArrayList<>();
        if (!YconomicsShopBridge.isAvailable() || !(level.getEntity(settlementCoreId) instanceof GhostTownHallCoreEntity core)) {
            return result;
        }
        for (PlotRecord plot : core.getPlots()) {
            List<YconomicsShopBridge.ShopListingView> listings = getListings(level, plot.plotId());
            if (listings.isEmpty()) {
                continue;
            }
            Map<Identifier, Integer> stock = scanPlotItemStock(level, plot.plotId());
            for (YconomicsShopBridge.ShopListingView listing : listings) {
                listing.resource().itemId().ifPresent(itemId -> {
                    if (stock.getOrDefault(itemId, 0) > 0) {
                        result.add(listing);
                    }
                });
            }
        }
        return result;
    }

    /**
     * Buys up to {@code quantity} units of {@code resource} from {@code shopId} (the Shop that owns
     * {@code plotId}'s boxes), draining stock from every box located on {@code plotId}'s own plot (via
     * Cartographyr's Box Identity over the plot's real polygon) and depositing nuggets into {@code
     * paymentBoxes}. Returns {@code (0, 0)} if there's no listing for this resource or Yconomics isn't
     * loaded.
     *
     * <p><b>Takes {@code shopId} directly now, not derived from {@code plotId}</b> -- a real bug found
     * live 2026-10-08: this used to re-resolve the shop via {@code YconomicsShopBridge#getShopIdFor},
     * Yconomics' own one-plot-per-shop internal registry, populated only for whichever single plot
     * originally called {@code registerShop}. That's correct for a player-founded plot (always 1:1),
     * but a natural settlement's Shop is deliberately shared across *every* plot of a Zone Type (see
     * {@code zone.NaturalSettlementPlotStore}'s own {@code sharedShops} doc) -- any plot other than
     * the one original "registering" plot resolved to no shop at all here, failing every purchase with
     * "Out of stock" regardless of real stock or listings (both of which were always correct). The
     * caller already has the right {@code shopId} in hand (it's exactly what populated the listings
     * the player is looking at), so passing it through directly removes the broken re-derivation
     * entirely instead of teaching it about sharing.
     */
    /**
     * Convenience overload for a caller that only has {@code plotId} (every cross-mod consumer so
     * far -- e.g. Lyfe's crafting/cooking structure-upgrade funding, always against a player-founded,
     * 1:1 plot/shop) -- re-derives {@code shopId} via Yconomics' own {@code getShopIdFor}, which is
     * correct for that 1:1 case. A natural settlement's own Shop screen already has the real
     * {@code shopId} in hand and should keep calling the explicit-{@code shopId} overload directly,
     * not this one -- see that overload's own doc for why.
     */
    public static YconomicsShopBridge.PurchaseResult purchaseFromSettlementShop(ServerLevel level, UUID plotId, ShopResource resource,
                                                                                 int quantity, List<Container> paymentBoxes) {
        if (!YconomicsShopBridge.isAvailable()) {
            return new YconomicsShopBridge.PurchaseResult(List.of(), 0);
        }
        Optional<UUID> shopId = YconomicsShopBridge.getShopIdFor(level, plotId);
        if (shopId.isEmpty()) {
            return new YconomicsShopBridge.PurchaseResult(List.of(), 0);
        }
        return purchaseFromSettlementShop(level, plotId, shopId.get(), resource, quantity, paymentBoxes);
    }

    public static YconomicsShopBridge.PurchaseResult purchaseFromSettlementShop(ServerLevel level, UUID plotId, UUID shopId, ShopResource resource,
                                                                                 int quantity, List<Container> paymentBoxes) {
        if (!YconomicsShopBridge.isAvailable()) {
            return new YconomicsShopBridge.PurchaseResult(List.of(), 0);
        }
        List<Container> stockBoxes = resolvePlotBoxes(level, plotId);
        return YconomicsShopBridge.purchase(level, shopId, resource, quantity, stockBoxes, paymentBoxes);
    }

    /**
     * The reverse of {@link #purchaseFromSettlementShop} (2026-10-08) -- sells {@code quantity} units
     * of {@code itemId} to {@code shopId}. Unlike the buy direction, there's only one box pool
     * involved here: {@code plotId}'s own boxes are both where the sold item ends up AND where the
     * nuggets paid to the seller come from, so this resolves {@code resolvePlotBoxes} once and uses it
     * for both roles -- no separate caller-supplied box list needed. See {@link
     * #purchaseFromSettlementShop}'s own doc for why {@code shopId} is now taken directly rather than
     * re-derived from {@code plotId}.
     */
    public static YconomicsShopBridge.SellResult sellToSettlementShop(ServerLevel level, UUID plotId, UUID shopId, ShopResource resource,
                                                                       List<ItemStack> soldStacks) {
        if (!YconomicsShopBridge.isAvailable()) {
            return new YconomicsShopBridge.SellResult(0, 0, 0);
        }
        List<Container> plotBoxes = resolvePlotBoxes(level, plotId);
        return YconomicsShopBridge.sell(level, shopId, resource, soldStacks, plotBoxes, plotBoxes);
    }

    /**
     * Every unique item currently sitting in {@code plotId}'s own boxes, summed across all of them --
     * drives {@code client.ManageShopScreen}'s scan-the-plot listing editor (2026-10-08, replacing the
     * old "hold the item, walk to the sign" add-listing flow). Concrete item ids only (a box slot is
     * always a concrete stack, never a tag) -- an existing tag-based listing is merged in separately by
     * the caller, since this method has no way to know which tag(s) an owner might care about.
     */
    public static Map<Identifier, Integer> scanPlotItemStock(ServerLevel level, UUID plotId) {
        return scanItemStock(resolvePlotBoxes(level, plotId));
    }

    /**
     * Same as {@link #scanPlotItemStock}, but over an already-resolved box list -- added 2026-10-10
     * after a live lag report traced to {@code zone.PlotCraftingTicker}: {@code resolvePlotBoxes}
     * (via {@link #findPolygonForPlot}) does a full-world {@code GhostTownHallCoreEntity} scan every
     * call, and that ticker was calling {@code scanPlotItemStock(level, plotId)} once per planned-
     * inventory target *on top of* the `boxes` it had already resolved itself for the same plot --
     * a full extra world scan per target, per plot, every second. {@code zone.PlannedInventoryClearing
     * #executeCraftOne} had the identical redundancy. Both now call this overload with their own
     * already-resolved {@code List<Container>} instead.
     */
    public static Map<Identifier, Integer> scanItemStock(List<Container> boxes) {
        Map<Identifier, Integer> counts = new LinkedHashMap<>();
        for (Container box : boxes) {
            for (int slot = 0; slot < box.getContainerSize(); slot++) {
                ItemStack stack = box.getItem(slot);
                if (!stack.isEmpty()) {
                    Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
                    counts.merge(id, stack.getCount(), Integer::sum);
                }
            }
        }
        return counts;
    }

    /** One exact Shop-listable variant's total stock plus a representative stack -- see {@link #scanItemStockByVariant}. */
    public record VariantStock(int count, ItemStack sample) {
    }

    /**
     * Same box-scanning pass as {@link #scanItemStock}, but grouped by exact Shop-listable variant
     * (2026-10-10, for per-quality Shop listings, e.g. Lyfe's crafted food) rather than plain item id
     * -- a stack whose real display name differs from its base item's own default name (Lyfe's baked
     * {@code "[3.75] (T5) - Bread"}, or Lyfe's own Research Notes, dynamically named per recipe
     * target/tier) gets its own {@code ShopResource.ofExactItem} key instead of being lumped into
     * every other stack of the same base item; a stack whose display name matches the default groups
     * by plain item id exactly as before. Real report, 2026-10-17: the original version of this check
     * only tested for vanilla's own {@code DataComponents#CUSTOM_NAME} (an explicit stored override,
     * e.g. an anvil rename), which Research Notes never sets -- its distinguishing name comes from a
     * dynamic {@code Item#getName(ItemStack)} override instead, so every Research Note regardless of
     * target/tier was silently lumped into one generic, indistinguishable "Research Notes" listing.
     * Comparing the real resolved hover name against a fresh default-component stack's own name
     * catches both mechanisms uniformly, without needing to know which one a given item actually
     * uses. {@code
     * sample} is one real stack of that exact variant (for reading e.g. Lyfe's baked icons value when
     * computing a suggested price) -- always present since a key only ever exists because at least
     * one matching stack was found. Used only by {@code client.ManageShopScreen}'s row-building and
     * NPC auto-listing -- every *generic* consumer (Planned Inventory targets, crafting, the plain
     * Buy/Sell screen's own stock count) deliberately keeps using {@link #scanItemStock}/{@code
     * zone.ContainerWithdraw}, which still treat any quality of the same item as one resource, per
     * explicit user design (an NPC's "happy state" target for Bread is satisfied by any quality of
     * Bread it has on hand).
     */
    public static Map<ShopResource, VariantStock> scanItemStockByVariant(List<Container> boxes) {
        Map<ShopResource, VariantStock> result = new LinkedHashMap<>();
        for (Container box : boxes) {
            for (int slot = 0; slot < box.getContainerSize(); slot++) {
                ItemStack stack = box.getItem(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
                String hoverName = stack.getHoverName().getString();
                String defaultName = new ItemStack(stack.getItem()).getHoverName().getString();
                ShopResource key = !hoverName.equals(defaultName)
                        ? ShopResource.ofExactItem(id, hoverName)
                        : ShopResource.ofItem(id);
                VariantStock existing = result.get(key);
                result.put(key, existing == null
                        ? new VariantStock(stack.getCount(), stack.copy())
                        : new VariantStock(existing.count() + stack.getCount(), existing.sample()));
            }
        }
        return result;
    }

    /**
     * Every real container located within {@code plotId}'s own polygon -- the same "all boxes on the
     * plot" pool {@code bills.PlotBoxDiscovery} already reads for rent. Falls back to a natural
     * village's own per-zone-type underground vault (see {@code zone.NaturalShopVault}) when
     * {@code plotId} isn't a player-founded plot at all -- a natural-village plot has no settlement
     * polygon to scan here (it was never staked/finalized through a {@code GhostTownHallCoreEntity}),
     * so without this fallback every natural Shop would always read as having zero stock.
     */
    public static List<Container> resolvePlotBoxes(ServerLevel level, UUID plotId) {
        List<Container> containers = new ArrayList<>();
        Optional<Geometry.Polygon> polygon = findPolygonForPlot(level, plotId);
        if (polygon.isEmpty()) {
            return com.github.cerealklla.settlemynts.zone.NaturalShopVault.resolveBoxesForPlot(level, plotId);
        }
        return resolveBoxesForPolygon(level, polygon.get());
    }

    /**
     * Withdraws up to {@code maxAmount} of the real item {@code itemId} from {@code plotId}'s own
     * boxes -- added 2026-10-22 for Blueprynts' Construction Box funding rework ({@code
     * bridge.SettlemyntsPlotBoxBridge#withdrawFromPlot}, "the construction box should automatically
     * suck up needed materials from within the plot as soon as they are added to the inventories,"
     * same precedent as {@code watchtower.WatchTowerBeaconBlockEntity}'s own fuel pull). Returns the
     * amount actually removed, 0 if {@code itemId} doesn't resolve to a real item or none was found.
     */
    public static int withdrawItemFromPlot(ServerLevel level, UUID plotId, Identifier itemId, int maxAmount) {
        if (maxAmount <= 0) {
            return 0;
        }
        List<ItemStack> taken = ContainerWithdraw.drain(resolvePlotBoxes(level, plotId), ShopResource.ofItem(itemId), maxAmount);
        int total = 0;
        for (ItemStack stack : taken) {
            total += stack.getCount();
        }
        return total;
    }

    /**
     * Deposits {@code stacks} into whichever of {@code plotId}'s own boxes have room (splitting
     * across several boxes the same way {@link ContainerDeposit#depositIntoAny} already does for a
     * single stack) -- returns whatever didn't fit, unmodified, for the caller to handle itself
     * (e.g. piling it on the ground). Added 2026-10-22 for Blueprynts' Construction Box, which
     * refunds its own already-supplied materials back into the plot this way whenever a bound
     * Blueprint is changed mid-build ("only the resources actually used so far").
     */
    public static List<ItemStack> depositItemsIntoPlot(ServerLevel level, UUID plotId, List<ItemStack> stacks) {
        List<Container> boxes = resolvePlotBoxes(level, plotId);
        List<ItemStack> leftover = new ArrayList<>();
        for (ItemStack stack : stacks) {
            ItemStack copy = stack.copy();
            ContainerDeposit.depositIntoAny(boxes, copy);
            if (!copy.isEmpty()) {
                leftover.add(copy);
            }
        }
        return leftover;
    }

    /**
     * Wholesale-replaces {@code plotId}'s {@code derivedFromConstructionBox} Planned Inventory
     * targets with {@code missing} -- added 2026-10-22 for Blueprynts' Construction Box funding
     * rework ({@code bridge.SettlemyntsPlotBoxBridge#syncMissingMaterials}), see {@code
     * construction.ConstructionMaterialDemand}'s own doc. A no-op if {@code settlementCoreId}/
     * {@code plotId} don't resolve to a real settlement/plot.
     */
    public static void setConstructionMaterialTargets(ServerLevel level, UUID settlementCoreId, UUID plotId, Map<Identifier, Integer> missing) {
        com.github.cerealklla.settlemynts.construction.ConstructionMaterialDemand.recalculate(level, settlementCoreId, plotId, missing);
    }

    /**
     * Same as the tail of {@link #resolvePlotBoxes}, but for a polygon the caller already has in hand
     * -- added 2026-10-10 alongside {@link #resolvePolygonDirect}, see that method's own doc for why.
     */
    public static List<Container> resolveBoxesForPolygon(ServerLevel level, Geometry.Polygon polygon) {
        List<Container> containers = new ArrayList<>();
        // Real report, 2026-10-17 (Auto-Drop): two single chests placed adjacent form a vanilla
        // double chest visually, but each half has its own independently-tracked BOX_ID (see
        // Cartography.resolveBoxContainer's own doc) -- without this dedup, both halves' ids would
        // each resolve to the same already-merged Container (resolveBoxContainer merges them), and
        // this list would then double-count that one physical chest's stock. Tracked by block
        // position, not boxId, so a paired half is skipped once its sibling has already contributed
        // the merged container.
        java.util.Set<BlockPos> visited = new java.util.HashSet<>();
        for (UUID boxId : Cartography.getBoxesAt(level, polygon)) {
            Optional<BlockPos> pos = Cartography.getBoxLocation(level, boxId).map(net.minecraft.core.GlobalPos::pos);
            if (pos.isEmpty() || !visited.add(pos.get())) {
                continue;
            }
            Cartography.resolveBoxContainer(level, boxId).ifPresent(containers::add);
            Cartography.getPairedBoxPosition(level, pos.get()).ifPresent(visited::add);
        }
        return containers;
    }

    /**
     * Every real container's own {@link BlockPos} located within {@code plotId}'s own polygon --
     * added 2026-10-11 for the Mineshaft worker ({@code mineshaft.MinerWorkGoal}), the first plot NPC
     * that needs to actually *walk to* a chest rather than deposit into it in place (every existing
     * worker only ever calls {@link #resolvePlotBoxes}, which hands back live {@link Container}s with
     * no position attached). Empty for a natural-village plot, same as {@link #resolvePlotBoxes}'s
     * own fallback condition -- a Mineshaft can't exist there (no player-founded settlement to stake
     * one on), so no natural-vault fallback is needed here.
     */
    public static List<BlockPos> resolvePlotBoxPositions(ServerLevel level, UUID plotId) {
        Optional<Geometry.Polygon> polygon = findPolygonForPlot(level, plotId);
        if (polygon.isEmpty()) {
            return List.of();
        }
        return resolveBoxPositionsForPolygon(level, polygon.get());
    }

    /**
     * Same as the tail of {@link #resolvePlotBoxPositions}, but for a polygon the caller already has
     * in hand -- added 2026-10-21 after live `TickProfiler` data showed {@code
     * mineshaft.MinerWorkGoal#canUse} repeatedly costing 100-130ms on this method's own full-world
     * {@code GhostTownHallCoreEntity} scan (via {@link #findPolygonForPlot}), called redundantly on
     * top of that same goal's own {@code resolvePlotRecord} just lines above it -- the exact
     * "resolve once, reuse" gap {@link #resolveBoxesForPolygon}/{@link #resolvePolygonDirect} were
     * already built to close for other callers (see those methods' own docs), just never applied here.
     */
    public static List<BlockPos> resolveBoxPositionsForPolygon(ServerLevel level, Geometry.Polygon polygon) {
        List<BlockPos> positions = new ArrayList<>();
        for (UUID boxId : Cartography.getBoxesAt(level, polygon)) {
            Cartography.getBoxLocation(level, boxId).ifPresent(globalPos -> positions.add(globalPos.pos()));
        }
        return positions;
    }

    /**
     * A finalized player-founded plot's own real polygon, if resolvable -- exposed publicly 2026-10-09
     * so {@code zone.PlotCraftingStructures} (NPC plot crafting) can scan the same area this plot's own
     * boxes are found in, without duplicating the settlement/core lookup here. {@code Optional.empty()}
     * for a natural-village plot (no settlement polygon exists for one) or an unresolvable plot, same
     * as {@link #resolvePlotBoxes}'s own fallback condition.
     */
    public static Optional<Geometry.Polygon> resolvePlotPolygon(ServerLevel level, UUID plotId) {
        return findPolygonForPlot(level, plotId);
    }

    /**
     * Same as {@link #resolvePlotPolygon}, but for a {@link PlotRecord} the caller already has in
     * hand (e.g. from {@code core.getPlots()}) -- skips {@link #findPolygonForPlot}'s own full-world
     * {@code GhostTownHallCoreEntity} scan entirely, going straight to the cheap per-entity-id lookup.
     * Added 2026-10-10 for NPC plot crafting's group-triggered pass ({@code
     * zone.PlannedInventoryClearing#craftFromOwnStockForGroup}), which already iterates a known
     * core's own plots directly -- re-discovering "which core owns this plot" from scratch via
     * {@link #resolvePlotPolygon} would be pure waste there.
     */
    public static Optional<Geometry.Polygon> resolvePolygonDirect(ServerLevel level, PlotRecord plot) {
        return resolvePolygon(level, plot);
    }

    /**
     * The current {@link PlotRecord} for {@code plotId} within {@code level} specifically, if any --
     * added 2026-10-10 for {@code zone.ShopCraftingDebounceTicker}'s debounced per-shop auto-crafting
     * check: it only ever has a bare {@code plotId} (plus the {@code level} it was raised on) to work
     * from, not a {@link PlotRecord} in hand, and needs the record's *current* state (Planned
     * Inventory may have changed since the triggering trade). Still a full-world
     * {@code GhostTownHallCoreEntity} scan under the hood ({@link #findPolygonForPlot}'s own sibling)
     * -- fine here since it only runs once per debounce fire (10s of inactivity on one specific shop),
     * nothing like the old time-based ticker's every-second/every-plot cost.
     */
    public static Optional<PlotRecord> findPlotRecord(ServerLevel level, UUID plotId) {
        AABB worldBounds = new AABB(-WORLD_SCAN_RADIUS, level.getMinY(), -WORLD_SCAN_RADIUS,
                WORLD_SCAN_RADIUS, level.getMaxY(), WORLD_SCAN_RADIUS);
        for (GhostTownHallCoreEntity core : level.getEntities(ModEntities.GHOST_TOWN_HALL_CORE.get(), worldBounds, GhostTownHallCoreEntity::isFinalized)) {
            for (PlotRecord plot : core.getPlots()) {
                if (plot.plotId().equals(plotId)) {
                    return Optional.of(plot);
                }
            }
        }
        return Optional.empty();
    }

    /** The real current shortfall of each generic resource needed to raise a plot's Tier cap by one right now -- see {@link #getPlotUpgradeShortfall}'s own doc. */
    public record PlotUpgradeShortfall(int nextTier, int woodNeeded, int stoneNeeded) {
        public boolean anyShortfall() {
            return woodNeeded > 0 || stoneNeeded > 0;
        }
    }

    /**
     * The real current shortfall (cost minus physical on-hand plot stock) of each generic resource
     * needed to raise {@code plotId}'s own Tier cap by one right now -- added 2026-10-12 for
     * Myssions' new "help upgrade this plot" quest type, which hands a player the actual missing
     * components to go gather, not just the plot's flat Tier-upgrade cost table on its own (which
     * says nothing about whatever the plot's boxes already hold). Mirrors {@code construction
     * .PlotTierUpgradeFunding#preview}'s own on-hand accounting, but doesn't involve gold/market
     * purchasing at all -- a quest reward is a flat gold/XP grant, not a funding transaction. Empty
     * if {@code plotId} can't be resolved.
     */
    public static Optional<PlotUpgradeShortfall> getPlotUpgradeShortfall(ServerLevel level, UUID plotId) {
        return findPlotRecord(level, plotId).map(plot -> {
            int nextTier = plot.tier() + 1;
            List<Container> boxes = resolvePlotBoxes(level, plotId);
            List<com.github.cerealklla.settlemynts.construction.ResourceCost> cost =
                    com.github.cerealklla.settlemynts.construction.PlotTierUpgradeCost.costFor(nextTier);
            int woodNeeded = 0;
            int stoneNeeded = 0;
            for (com.github.cerealklla.settlemynts.construction.ResourceCost entry : cost) {
                int shortfall = Math.max(0, entry.amount() - countAvailable(boxes, entry.resource()));
                if (entry.resource() == com.github.cerealklla.blueprynts.blueprint.GenericResource.WOOD) {
                    woodNeeded = shortfall;
                } else if (entry.resource() == com.github.cerealklla.blueprynts.blueprint.GenericResource.STONE) {
                    stoneNeeded = shortfall;
                }
            }
            return new PlotUpgradeShortfall(nextTier, woodNeeded, stoneNeeded);
        });
    }

    /**
     * Applies {@code plotId}'s real Tier-cap raise when its cost was already paid entirely OUTSIDE
     * the normal "Upgrade Plot" flow -- added 2026-10-13 for Myssions' Plot Upgrade quest ({@code
     * bridge.SettlemyntsPlotUpgradeBridge#applyUpgrade}), whose own delivery milestone collects the
     * real shortfall directly from the player, never depositing into the Construction Box. See
     * {@code construction.PlotTierUpgradeFunding#applyPaidExternally}'s own doc for the full
     * accounting (the box's own on-hand stock still gets drained, since it already counted toward the
     * shortfall figure the quest used). {@code targetTier} is the specific Tier the caller's own
     * shortfall figure was computed for -- guards against applying this upgrade a second time (two
     * players' quest instances for the same plot, or a stale duplicate) once the plot's already moved
     * past it. False if the plot can't be resolved anymore, is already at its real Tier ceiling, or
     * has already moved past {@code targetTier}.
     */
    public static boolean applyPlotUpgradeFromQuest(ServerLevel level, UUID plotId, int targetTier) {
        AABB worldBounds = new AABB(-WORLD_SCAN_RADIUS, level.getMinY(), -WORLD_SCAN_RADIUS,
                WORLD_SCAN_RADIUS, level.getMaxY(), WORLD_SCAN_RADIUS);
        for (GhostTownHallCoreEntity core : level.getEntities(ModEntities.GHOST_TOWN_HALL_CORE.get(), worldBounds, GhostTownHallCoreEntity::isFinalized)) {
            for (PlotRecord plot : core.getPlots()) {
                if (plot.plotId().equals(plotId)) {
                    return com.github.cerealklla.settlemynts.construction.PlotTierUpgradeFunding
                            .applyPaidExternally(level, core, plot, targetTier).isPresent();
                }
            }
        }
        return false;
    }

    private static int countAvailable(List<Container> boxes, com.github.cerealklla.blueprynts.blueprint.GenericResource resource) {
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

    /**
     * A plot's real, on-the-ground location: its Plot Config Sign's own position/dimension (every
     * finalized plot has one, regardless of whether Blueprynts is loaded -- see {@code
     * plotsign.PlotConfigSignSpawner}'s own doc) plus its owning settlement's display name (empty
     * string for a natural village, which has no player-set {@code GhostTownHallCoreEntity#
     * settlementName} to read). Added 2026-10-10 for Myssions' on-site quest retrofit -- a quest
     * whose threat is literally at the issuing NPC's own plot (not some vague distant location) can
     * point a player back to the real place by name, even after they've left.
     */
    public record PlotLocation(BlockPos pos, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, String settlementName) {
    }

    public static Optional<PlotLocation> findPlotLocation(ServerLevel level, UUID plotId) {
        net.minecraft.core.GlobalPos signPos = com.github.cerealklla.settlemynts.plotsign.PlotConfigSignIndex.get(level.getServer())
                .get(plotId).orElse(null);
        if (signPos == null) {
            return Optional.empty();
        }
        ServerLevel signLevel = level.getServer().getLevel(signPos.dimension());
        if (signLevel == null) {
            return Optional.empty();
        }
        String settlementName = "";
        if (signLevel.getBlockEntity(signPos.pos()) instanceof com.github.cerealklla.settlemynts.plotsign.PlotConfigSignBlockEntity sign
                && sign.settlementCoreId() != null
                && signLevel.getEntity(sign.settlementCoreId()) instanceof GhostTownHallCoreEntity core) {
            settlementName = core.getSettlementName();
        }
        return Optional.of(new PlotLocation(signPos.pos(), signPos.dimension(), settlementName));
    }

    private static Optional<Geometry.Polygon> findPolygonForPlot(ServerLevel level, UUID plotId) {
        AABB worldBounds = new AABB(-WORLD_SCAN_RADIUS, level.getMinY(), -WORLD_SCAN_RADIUS,
                WORLD_SCAN_RADIUS, level.getMaxY(), WORLD_SCAN_RADIUS);
        for (GhostTownHallCoreEntity core : level.getEntities(ModEntities.GHOST_TOWN_HALL_CORE.get(), worldBounds, GhostTownHallCoreEntity::isFinalized)) {
            for (PlotRecord plot : core.getPlots()) {
                if (plot.plotId().equals(plotId)) {
                    return resolvePolygon(level, plot);
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<Geometry.Polygon> resolvePolygon(ServerLevel level, PlotRecord plot) {
        return Cartography.getEntity(level, new EntityId(plot.cartographyrPlotEntityId()))
                .map(GeographicEntity::geometry)
                .filter(Geometry.Polygon.class::isInstance)
                .map(Geometry.Polygon.class::cast);
    }

    /**
     * Fixed 2026-10-09 -- real report: a player listed logs in a Manage Shop screen (which always
     * lists by the specific item physically found in a box, e.g. {@code oak_log}, never a tag), but
     * {@code PlotTierMarketPurchasing}'s search for the "Logs" cost entry (a {@code generic_wood}
     * tag-based {@link ShopResource}) never matched it -- this method used to require both sides be
     * the SAME kind (both tag or both item), so a tag-based search could never find an item-based
     * listing no matter what. Now also matches cross-type, via {@link ShopResource#matches(ItemStack)}
     * -- does the item-based side's item actually fall under the tag-based side's tag.
     */
    private static boolean sameResource(ShopResource a, ShopResource b) {
        if (a.tag().isPresent() && b.tag().isPresent()) {
            return a.tag().get().equals(b.tag().get());
        }
        if (a.itemId().isPresent() && b.itemId().isPresent()) {
            return a.itemId().get().equals(b.itemId().get());
        }
        if (a.tag().isPresent() && b.itemId().isPresent()) {
            return a.matches(new ItemStack(BuiltInRegistries.ITEM.getValue(b.itemId().get())));
        }
        if (b.tag().isPresent() && a.itemId().isPresent()) {
            return b.matches(new ItemStack(BuiltInRegistries.ITEM.getValue(a.itemId().get())));
        }
        return false;
    }

    // --- Inter-settlement Highway Stake support (added 2026-10-11, for Myssions' new road-
    // connection quest -- see roadway.HighwayQuestSupport for the real implementation). Settlements
    // are identified by their Cartographyr placeId here, not a Settlemynts UUID, since Myssions
    // only ever has the former (a settlement's canonical cross-mod identity).

    /** The live settlement core for a Cartographyr-registered settlement, if one currently exists and is loaded -- empty for a natural (NPC-owned) settlement, which has none. */
    public static Optional<UUID> findCoreIdForPlaceId(ServerLevel level, long placeId) {
        return com.github.cerealklla.settlemynts.roadway.HighwayQuestSupport.findCoreIdForPlaceId(level, placeId);
    }

    /** Grants {@code player} a fresh Highway Stake stack correctly tagged for a road-connection quest instance between two settlements. Either core id may be absent. */
    public static void grantHighwayStakes(net.minecraft.server.level.ServerPlayer player, UUID instanceId,
                                           Optional<UUID> coreIdA, Optional<UUID> coreIdB, int tier, int count) {
        com.github.cerealklla.settlemynts.roadway.HighwayQuestSupport.grantHighwayStakes(player, instanceId, coreIdA, coreIdB, tier, count);
    }

    /** Removes and counts every remaining Highway Stake item tagged with {@code instanceId} from the player's inventory -- the buyback payout at road completion. */
    public static int buybackUnusedHighwayStakes(net.minecraft.server.level.ServerPlayer player, UUID instanceId) {
        return com.github.cerealklla.settlemynts.roadway.HighwayQuestSupport.buybackUnusedStakes(player, instanceId);
    }

    /**
     * Discards every ghost Highway Stake/connection-marker torch for {@code instanceId} with NO
     * terrain revert -- call once the road-connection quest actually COMPLETES. Explicit user
     * request: the ghost markers should disappear once the road is finished, and leaving them live
     * lets a player accidentally right-click one and un-pave part of their own just-finished road
     * (the normal stake-pickup interaction). Never use {@link #buybackUnusedHighwayStakes} as a
     * substitute for this -- that only clears leftover inventory items, not placed ghost entities.
     */
    public static void hideCompletedHighwayMarkers(ServerLevel level, UUID instanceId, BlockPos nearHint) {
        com.github.cerealklla.settlemynts.roadway.HighwayStakeEntity.hideAllForInstance(level, instanceId, nearHint);
        com.github.cerealklla.settlemynts.roadway.HighwayConnectionMarkerEntity.discardAll(level, instanceId, nearHint);
    }

    /** Live progress of an in-progress road-connection instance's Highway Stake network -- see {@code roadway.HighwayQuestSupport.HighwayProgress}'s own doc. */
    public static com.github.cerealklla.settlemynts.roadway.HighwayQuestSupport.HighwayProgress checkHighwayProgress(
            ServerLevel level, UUID instanceId, BlockPos nearHint, long placeIdA, long placeIdB) {
        return com.github.cerealklla.settlemynts.roadway.HighwayQuestSupport.checkProgress(level, instanceId, nearHint, placeIdA, placeIdB);
    }

    /** The Settlement Tier of a Cartographyr-registered settlement -- the average of its own plot tiers (see {@code roadway.SettlementTierResolver}). {@code 1} if the settlement can't currently be resolved to a live plot list at all (an unloaded or natural settlement with no tracked plots). */
    public static int getSettlementTier(ServerLevel level, long placeId) {
        Optional<UUID> coreId = findCoreIdForPlaceId(level, placeId);
        if (coreId.isPresent() && level.getEntity(coreId.get()) instanceof GhostTownHallCoreEntity core) {
            return com.github.cerealklla.settlemynts.roadway.SettlementTierResolver.averageTier(core.getPlots());
        }
        return 1;
    }

    /**
     * Despawns every Highway Stake tagged with {@code instanceId}, reverting each one's own paved
     * terrain first (same effect as a player manually picking up a connected stake) -- added
     * 2026-10-13 for Myssions' {@code quest.QuestEndHooks} cleanup on a road-connection quest's
     * failure/abandon, so a never-finished Highway leaves no terrain scar behind. See {@code
     * roadway.HighwayStakeEntity#despawnAllForInstance}'s own doc for the real fix this call relies
     * on. {@code nearHint} only needs to be anywhere within the stake network's real span (2000-block
     * search radius) -- the instance's own quest hint position (set near the destination settlement)
     * is always within range of the whole road.
     */
    public static void despawnHighwayStakesForInstance(ServerLevel level, UUID instanceId, BlockPos nearHint) {
        com.github.cerealklla.settlemynts.roadway.HighwayStakeEntity.despawnAllForInstance(level, instanceId, nearHint);
    }
}
