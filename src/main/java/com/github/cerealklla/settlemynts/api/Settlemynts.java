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
import com.github.cerealklla.settlemynts.construction.ConstructionRequirements;
import com.github.cerealklla.settlemynts.construction.ZoneTierConstructionConfig;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.registration.ModEntities;
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

    /** A Zone Type + Tier's construction cost/time table (Section 14a, captured 2026-09-29) -- see {@link ConstructionRequirements}'s own doc. */
    public static ConstructionRequirements getConstructionRequirements(Identifier zoneTypeId, int tier) {
        return ZoneTierConstructionConfig.get(zoneTypeId, tier);
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
        for (GhostTownHallCoreEntity core : level.getEntities(ModEntities.GHOST_TOWN_HALL_CORE.get(), worldBounds, GhostTownHallCoreEntity::isFinalized)) {
            Optional<PlotRecord> plot = PlotGeometry.findContainingPlot(level, core, pos.getX(), pos.getZ());
            if (plot.isPresent()) {
                return PlotPermissions.canManage(plot.get(), core, playerId);
            }
        }
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
                                                                       Identifier itemId, int quantity) {
        if (!YconomicsShopBridge.isAvailable()) {
            return new YconomicsShopBridge.SellResult(0, 0, 0);
        }
        List<Container> plotBoxes = resolvePlotBoxes(level, plotId);
        return YconomicsShopBridge.sell(level, shopId, resource, itemId, quantity, plotBoxes, plotBoxes);
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
        for (UUID boxId : Cartography.getBoxesAt(level, polygon.get())) {
            Cartography.resolveBoxContainer(level, boxId).ifPresent(containers::add);
        }
        return containers;
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
}
