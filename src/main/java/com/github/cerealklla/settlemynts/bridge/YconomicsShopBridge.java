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

    /**
     * Local mirror of Yconomics' {@code shop.ShopListing} -- see {@link ShopResource}'s own class doc
     * for why. {@code buyPricePerUnit} (added 2026-10-08) is always {@code
     * shop.ShopPricing#deriveBuyPrice(pricePerUnit)} -- never independently settable, so it can't
     * drift out of the required 60%/floor/never-equal relationship.
     */
    public record ShopListingView(ShopResource resource, int pricePerUnit, int buyPricePerUnit) {
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

    /** Mirror of {@link PurchaseResult} for the reverse direction -- see {@code api.Yconomics.SellResult}'s own doc. */
    public record SellResult(int itemsSold, int nuggetsReceived, int sellPricePerUnit) {
    }

    /**
     * "Sell N" -- the reverse of {@link #purchase}, added 2026-10-08. {@code itemId} is the concrete
     * item actually being sold (resolved by the caller from the seller's held stack, since
     * {@code resource} can be tag-based).
     */
    public static SellResult sell(ServerLevel level, UUID shopId, ShopResource resource, net.minecraft.resources.Identifier itemId,
                                   int quantity, List<Container> stockBoxes, List<Container> paymentBoxes) {
        Yconomics.SellResult result = Yconomics.sellToShop(level, shopId, toYconomics(resource), itemId, quantity, stockBoxes, paymentBoxes);
        return new SellResult(result.itemsSold(), result.nuggetsReceived(), result.sellPricePerUnit());
    }

    /**
     * What a player with Lyfe's Merchant-skill bonus {@code bonusFraction} actually pays to buy one
     * unit of {@code sellPricePerUnit}'s item -- see {@code shop.ShopPricing#effectiveBuyCost}'s own
     * doc (includes the real rounding-based exploit this guards against).
     */
    public static int effectiveBuyCost(int sellPricePerUnit, double bonusFraction) {
        return com.github.cerealklla.yconomics.shop.ShopPricing.effectiveBuyCost(sellPricePerUnit, bonusFraction);
    }

    /** The mirror of {@link #effectiveBuyCost} for the sell direction -- see {@code shop.ShopPricing#effectiveSellPayout}. */
    public static int effectiveSellPayout(int sellPricePerUnit, double bonusFraction) {
        return com.github.cerealklla.yconomics.shop.ShopPricing.effectiveSellPayout(sellPricePerUnit, bonusFraction);
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
        ShopResource local = toLocalResource(r);
        return new ShopListingView(local, listing.pricePerUnit(),
                com.github.cerealklla.yconomics.shop.ShopPricing.deriveBuyPrice(listing.pricePerUnit()));
    }

    public static ShopResource toLocalResource(com.github.cerealklla.yconomics.shop.ShopResource r) {
        if (r.tag().isPresent()) {
            return ShopResource.ofTag(r.tag().get());
        }
        return r.customName().isPresent()
                ? ShopResource.ofExactItem(r.itemId().get(), r.customName().get())
                : ShopResource.ofItem(r.itemId().get());
    }

    private static com.github.cerealklla.yconomics.shop.ShopResource toYconomics(ShopResource resource) {
        if (resource.tag().isPresent()) {
            return com.github.cerealklla.yconomics.shop.ShopResource.ofTag(resource.tag().get());
        }
        return resource.customName().isPresent()
                ? com.github.cerealklla.yconomics.shop.ShopResource.ofExactItem(resource.itemId().get(), resource.customName().get())
                : com.github.cerealklla.yconomics.shop.ShopResource.ofItem(resource.itemId().get());
    }
}
