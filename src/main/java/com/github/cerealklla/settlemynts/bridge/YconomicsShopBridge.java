package com.github.cerealklla.settlemynts.bridge;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.github.cerealklla.settlemynts.zone.ShopResource;
import com.github.cerealklla.yconomics.api.Yconomics;
import com.github.cerealklla.yconomics.shop.PlotShop;
import com.github.cerealklla.yconomics.shop.ShopListing;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.neoforged.fml.ModList;

/**
 * Registers/queries a plot's Shop against Yconomics' shop engine (2026-10-05, see decisions.md) --
 * same isolation rule as {@code YconomicsBillBridge}'s own class doc: only ever referenced from
 * behind a {@code ModList.get().isLoaded("yconomics")} check at the call site. Converts between
 * Settlemynts' own local {@link ShopResource}/{@link ShopListingView} and Yconomics' real
 * {@code shop.ShopResource}/{@code shop.ShopListing} at this boundary only, so no other Settlemynts
 * class needs a Yconomics type reference either.
 */
public final class YconomicsShopBridge {

    private YconomicsShopBridge() {
    }

    public static boolean isAvailable() {
        return ModList.get().isLoaded("yconomics");
    }

    /** Local mirror of Yconomics' {@code shop.ShopListing} -- see {@link ShopResource}'s own class doc for why. */
    public record ShopListingView(ShopResource resource, int pricePerUnit) {
    }

    public static UUID registerShop(ServerLevel level, UUID plotId) {
        return Yconomics.registerPlotShop(level, Optional.of(plotId));
    }

    public static Optional<UUID> getShopIdFor(ServerLevel level, UUID plotId) {
        return Yconomics.getPlotShopFor(level, plotId).map(PlotShop::shopId);
    }

    public static List<ShopListingView> getListings(ServerLevel level, UUID shopId) {
        return Yconomics.getPlotShop(level, shopId)
                .map(shop -> shop.listings().stream().map(YconomicsShopBridge::toLocal).toList())
                .orElse(List.of());
    }

    public static void setListingPrice(ServerLevel level, UUID shopId, ShopResource resource, int pricePerUnit) {
        Yconomics.setListingPrice(level, shopId, toYconomics(resource), pricePerUnit);
    }

    public static void removeListing(ServerLevel level, UUID shopId, ShopResource resource) {
        Yconomics.removeListing(level, shopId, toYconomics(resource));
    }

    /** {@code itemsReceived} is what the caller must actually give the buyer -- see {@code api.Yconomics.PurchaseResult}'s own doc. */
    public record PurchaseResult(List<net.minecraft.world.item.ItemStack> itemsReceived, int nuggetsCharged) {
        public int filled() {
            return itemsReceived.stream().mapToInt(net.minecraft.world.item.ItemStack::getCount).sum();
        }
    }

    public static PurchaseResult purchase(ServerLevel level, UUID shopId, ShopResource resource, int quantity,
                                           List<Container> stockBoxes, List<Container> paymentBoxes) {
        Yconomics.PurchaseResult result = Yconomics.purchaseFromShop(level, shopId, toYconomics(resource), quantity, stockBoxes, paymentBoxes);
        return new PurchaseResult(result.itemsReceived(), result.nuggetsCharged());
    }

    /** The buyer's own Gold Nugget balance (loose inventory + Coin Purse) -- see {@code api.Yconomics#getNuggetBalance}. */
    public static int getNuggetBalance(Player player) {
        return Yconomics.getNuggetBalance(player);
    }

    /** All-or-nothing debit of the buyer's own Gold Nuggets -- see {@code api.Yconomics#withdrawNuggets}. */
    public static boolean withdrawNuggets(Player player, int amount) {
        return Yconomics.withdrawNuggets(player, amount);
    }

    private static ShopListingView toLocal(ShopListing listing) {
        com.github.cerealklla.yconomics.shop.ShopResource r = listing.resource();
        ShopResource local = r.tag().isPresent() ? ShopResource.ofTag(r.tag().get()) : ShopResource.ofItem(r.itemId().get());
        return new ShopListingView(local, listing.pricePerUnit());
    }

    private static com.github.cerealklla.yconomics.shop.ShopResource toYconomics(ShopResource resource) {
        return resource.tag().isPresent()
                ? com.github.cerealklla.yconomics.shop.ShopResource.ofTag(resource.tag().get())
                : com.github.cerealklla.yconomics.shop.ShopResource.ofItem(resource.itemId().get());
    }
}
