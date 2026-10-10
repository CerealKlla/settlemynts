package com.github.cerealklla.settlemynts.plotsign;

import java.util.Optional;

/**
 * Client-side bridge for "the Plot Config Sign's Main Menu should open" -- same zero-client-import
 * poll-once-handoff pattern as {@code founding.ClientFoundingRequests} (kept as its own small class
 * here rather than folded into that one, since it's a different package/feature area).
 */
public final class ClientPlotSignRequests {

    private static volatile OpenPlotConfigSignMenuPayload pendingMenu;
    private static volatile OpenPlotDetailsPayload pendingPlotDetails;
    private static volatile OpenPlotManagementPayload pendingPlotManagement;
    private static volatile OpenShopPayload pendingShop;
    private static volatile ShopGoldUpdatePayload pendingGoldUpdate;
    private static volatile ShopStockUpdatePayload pendingStockUpdate;
    private static volatile UpgradePlotPreviewPayload pendingUpgradePlotPreview;
    private static volatile OpenPlannedInventoryPayload pendingPlannedInventory;
    private static volatile OpenShopWishlistPayload pendingWishlist;

    private ClientPlotSignRequests() {
    }

    public static void requestMenu(OpenPlotConfigSignMenuPayload payload) {
        pendingMenu = payload;
    }

    public static Optional<OpenPlotConfigSignMenuPayload> takePendingMenu() {
        OpenPlotConfigSignMenuPayload request = pendingMenu;
        pendingMenu = null;
        return Optional.ofNullable(request);
    }

    public static void requestPlotDetails(OpenPlotDetailsPayload payload) {
        pendingPlotDetails = payload;
    }

    public static Optional<OpenPlotDetailsPayload> takePendingPlotDetails() {
        OpenPlotDetailsPayload request = pendingPlotDetails;
        pendingPlotDetails = null;
        return Optional.ofNullable(request);
    }

    public static void requestPlotManagement(OpenPlotManagementPayload payload) {
        pendingPlotManagement = payload;
    }

    public static Optional<OpenPlotManagementPayload> takePendingPlotManagement() {
        OpenPlotManagementPayload request = pendingPlotManagement;
        pendingPlotManagement = null;
        return Optional.ofNullable(request);
    }

    public static void requestShop(OpenShopPayload payload) {
        pendingShop = payload;
    }

    public static Optional<OpenShopPayload> takePendingShop() {
        OpenShopPayload request = pendingShop;
        pendingShop = null;
        return Optional.ofNullable(request);
    }

    public static void requestGoldUpdate(ShopGoldUpdatePayload payload) {
        pendingGoldUpdate = payload;
    }

    public static Optional<ShopGoldUpdatePayload> takePendingGoldUpdate() {
        ShopGoldUpdatePayload request = pendingGoldUpdate;
        pendingGoldUpdate = null;
        return Optional.ofNullable(request);
    }

    public static void requestStockUpdate(ShopStockUpdatePayload payload) {
        pendingStockUpdate = payload;
    }

    public static Optional<ShopStockUpdatePayload> takePendingStockUpdate() {
        ShopStockUpdatePayload request = pendingStockUpdate;
        pendingStockUpdate = null;
        return Optional.ofNullable(request);
    }

    public static void requestUpgradePlotPreview(UpgradePlotPreviewPayload payload) {
        pendingUpgradePlotPreview = payload;
    }

    public static Optional<UpgradePlotPreviewPayload> takePendingUpgradePlotPreview() {
        UpgradePlotPreviewPayload request = pendingUpgradePlotPreview;
        pendingUpgradePlotPreview = null;
        return Optional.ofNullable(request);
    }

    public static void requestPlannedInventory(OpenPlannedInventoryPayload payload) {
        pendingPlannedInventory = payload;
    }

    public static Optional<OpenPlannedInventoryPayload> takePendingPlannedInventory() {
        OpenPlannedInventoryPayload request = pendingPlannedInventory;
        pendingPlannedInventory = null;
        return Optional.ofNullable(request);
    }

    public static void requestWishlist(OpenShopWishlistPayload payload) {
        pendingWishlist = payload;
    }

    public static Optional<OpenShopWishlistPayload> takePendingWishlist() {
        OpenShopWishlistPayload request = pendingWishlist;
        pendingWishlist = null;
        return Optional.ofNullable(request);
    }
}
