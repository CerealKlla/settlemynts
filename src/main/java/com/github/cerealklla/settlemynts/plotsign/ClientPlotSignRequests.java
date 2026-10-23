package com.github.cerealklla.settlemynts.plotsign;

import java.util.Optional;

import com.github.cerealklla.nuclyus.request.ClientRequestSlot;

/**
 * Client-side bridge for "the Plot Config Sign's Main Menu should open" -- same zero-client-import
 * poll-once-handoff pattern as {@code founding.ClientFoundingRequests} (kept as its own small class
 * here rather than folded into that one, since it's a different package/feature area).
 */
public final class ClientPlotSignRequests {

    private static final ClientRequestSlot<OpenPlotConfigSignMenuPayload> MENU = new ClientRequestSlot<>();
    private static final ClientRequestSlot<OpenPlotDetailsPayload> PLOT_DETAILS = new ClientRequestSlot<>();
    private static final ClientRequestSlot<OpenPlotManagementPayload> PLOT_MANAGEMENT = new ClientRequestSlot<>();
    private static final ClientRequestSlot<OpenShopPayload> SHOP = new ClientRequestSlot<>();
    private static final ClientRequestSlot<ShopGoldUpdatePayload> GOLD_UPDATE = new ClientRequestSlot<>();
    private static final ClientRequestSlot<ShopStockUpdatePayload> STOCK_UPDATE = new ClientRequestSlot<>();
    private static final ClientRequestSlot<UpgradePlotPreviewPayload> UPGRADE_PLOT_PREVIEW = new ClientRequestSlot<>();
    private static final ClientRequestSlot<OpenPlannedInventoryPayload> PLANNED_INVENTORY = new ClientRequestSlot<>();
    private static final ClientRequestSlot<OpenShopWishlistPayload> WISHLIST = new ClientRequestSlot<>();
    private static final ClientRequestSlot<EstablishHighwayCandidatesPayload> ESTABLISH_HIGHWAY_MAP = new ClientRequestSlot<>();
    private static final ClientRequestSlot<OpenNpcMenuPayload> NPC_MENU = new ClientRequestSlot<>();
    private static final ClientRequestSlot<OpenConfigurePlotPayload> CONFIGURE_PLOT = new ClientRequestSlot<>();

    private ClientPlotSignRequests() {
    }

    public static void requestMenu(OpenPlotConfigSignMenuPayload payload) {
        MENU.request(payload);
    }

    public static Optional<OpenPlotConfigSignMenuPayload> takePendingMenu() {
        return MENU.takePending();
    }

    public static void requestPlotDetails(OpenPlotDetailsPayload payload) {
        PLOT_DETAILS.request(payload);
    }

    public static Optional<OpenPlotDetailsPayload> takePendingPlotDetails() {
        return PLOT_DETAILS.takePending();
    }

    public static void requestPlotManagement(OpenPlotManagementPayload payload) {
        PLOT_MANAGEMENT.request(payload);
    }

    public static Optional<OpenPlotManagementPayload> takePendingPlotManagement() {
        return PLOT_MANAGEMENT.takePending();
    }

    public static void requestShop(OpenShopPayload payload) {
        SHOP.request(payload);
    }

    public static Optional<OpenShopPayload> takePendingShop() {
        return SHOP.takePending();
    }

    public static void requestGoldUpdate(ShopGoldUpdatePayload payload) {
        GOLD_UPDATE.request(payload);
    }

    public static Optional<ShopGoldUpdatePayload> takePendingGoldUpdate() {
        return GOLD_UPDATE.takePending();
    }

    public static void requestStockUpdate(ShopStockUpdatePayload payload) {
        STOCK_UPDATE.request(payload);
    }

    public static Optional<ShopStockUpdatePayload> takePendingStockUpdate() {
        return STOCK_UPDATE.takePending();
    }

    public static void requestUpgradePlotPreview(UpgradePlotPreviewPayload payload) {
        UPGRADE_PLOT_PREVIEW.request(payload);
    }

    public static Optional<UpgradePlotPreviewPayload> takePendingUpgradePlotPreview() {
        return UPGRADE_PLOT_PREVIEW.takePending();
    }

    public static void requestPlannedInventory(OpenPlannedInventoryPayload payload) {
        PLANNED_INVENTORY.request(payload);
    }

    public static Optional<OpenPlannedInventoryPayload> takePendingPlannedInventory() {
        return PLANNED_INVENTORY.takePending();
    }

    public static void requestWishlist(OpenShopWishlistPayload payload) {
        WISHLIST.request(payload);
    }

    public static Optional<OpenShopWishlistPayload> takePendingWishlist() {
        return WISHLIST.takePending();
    }

    public static void requestEstablishHighwayMap(EstablishHighwayCandidatesPayload payload) {
        ESTABLISH_HIGHWAY_MAP.request(payload);
    }

    public static Optional<EstablishHighwayCandidatesPayload> takePendingEstablishHighwayMap() {
        return ESTABLISH_HIGHWAY_MAP.takePending();
    }

    public static void requestNpcMenu(OpenNpcMenuPayload payload) {
        NPC_MENU.request(payload);
    }

    public static Optional<OpenNpcMenuPayload> takePendingNpcMenu() {
        return NPC_MENU.takePending();
    }

    public static void requestConfigurePlot(OpenConfigurePlotPayload payload) {
        CONFIGURE_PLOT.request(payload);
    }

    public static Optional<OpenConfigurePlotPayload> takePendingConfigurePlot() {
        return CONFIGURE_PLOT.takePending();
    }
}
