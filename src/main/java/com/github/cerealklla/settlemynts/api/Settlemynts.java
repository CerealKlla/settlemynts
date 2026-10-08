package com.github.cerealklla.settlemynts.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
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
import com.github.cerealklla.settlemynts.zone.PlotRecord;
import com.github.cerealklla.settlemynts.zone.ShopResource;
import com.github.cerealklla.settlemynts.zone.ShopSeedCatalog;
import com.github.cerealklla.settlemynts.zone.ShopSeedCatalogRegistry;
import com.github.cerealklla.settlemynts.zone.ZoneType;
import com.github.cerealklla.settlemynts.zone.ZoneTypeRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
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

    /** A finalized plot's public-facing details -- everything a cross-mod caller needs without reaching into {@code zone.PlotRecord}/{@code founding.GhostTownHallCoreEntity} directly. */
    public record PlotHandle(UUID plotId, Identifier zoneTypeId, Optional<UUID> owner, UUID settlementCoreId, Geometry.Polygon polygon) {
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
                    return Optional.of(new PlotHandle(plot.get().plotId(), plot.get().zoneTypeId(), plot.get().owner(), core.getUUID(), polygon.get()));
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

    /**
     * Buys up to {@code quantity} units of {@code resource} from {@code plotId}'s Shop, draining its
     * stock from every box located on its own plot (via Cartographyr's Box Identity over the plot's
     * real polygon) and depositing nuggets into {@code paymentBoxes}. Returns {@code (0, 0)} if the
     * plot has no Shop, no listing for this resource, or Yconomics isn't loaded.
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
        List<Container> stockBoxes = resolvePlotBoxes(level, plotId);
        return YconomicsShopBridge.purchase(level, shopId.get(), resource, quantity, stockBoxes, paymentBoxes);
    }

    /**
     * The reverse of {@link #purchaseFromSettlementShop} (2026-10-08) -- sells {@code quantity} units
     * of {@code itemId} to {@code plotId}'s Shop. Unlike the buy direction, there's only one box pool
     * involved here: the plot's own boxes are both where the sold item ends up AND where the nuggets
     * paid to the seller come from, so this resolves {@code resolvePlotBoxes} once and uses it for
     * both roles -- no separate caller-supplied box list needed.
     */
    public static YconomicsShopBridge.SellResult sellToSettlementShop(ServerLevel level, UUID plotId, ShopResource resource,
                                                                       Identifier itemId, int quantity) {
        if (!YconomicsShopBridge.isAvailable()) {
            return new YconomicsShopBridge.SellResult(0, 0, 0);
        }
        Optional<UUID> shopId = YconomicsShopBridge.getShopIdFor(level, plotId);
        if (shopId.isEmpty()) {
            return new YconomicsShopBridge.SellResult(0, 0, 0);
        }
        List<Container> plotBoxes = resolvePlotBoxes(level, plotId);
        return YconomicsShopBridge.sell(level, shopId.get(), resource, itemId, quantity, plotBoxes, plotBoxes);
    }

    /** Every real container located within {@code plotId}'s own polygon -- the same "all boxes on the plot" pool {@code bills.PlotBoxDiscovery} already reads for rent. */
    public static List<Container> resolvePlotBoxes(ServerLevel level, UUID plotId) {
        List<Container> containers = new ArrayList<>();
        Optional<Geometry.Polygon> polygon = findPolygonForPlot(level, plotId);
        if (polygon.isEmpty()) {
            return containers;
        }
        for (UUID boxId : Cartography.getBoxesAt(level, polygon.get())) {
            Cartography.resolveBoxContainer(level, boxId).ifPresent(containers::add);
        }
        return containers;
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
