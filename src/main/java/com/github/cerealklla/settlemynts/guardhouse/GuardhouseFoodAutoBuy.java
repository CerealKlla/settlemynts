package com.github.cerealklla.settlemynts.guardhouse;

import java.util.List;

import com.github.cerealklla.settlemynts.api.Settlemynts;
import com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.zone.ContainerDeposit;
import com.github.cerealklla.settlemynts.zone.ContainerWithdraw;
import com.github.cerealklla.settlemynts.zone.PlotRecord;
import com.github.cerealklla.settlemynts.zone.ShopResource;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * "This will be used at midnight to buy enough food to meet that threshold if enough room exists in
 * the Food Store box" (explicit user spec, 2026-10-16). Driven from {@code
 * zone.ShopMidnightRestockTicker}'s existing per-settlement-core loop, same day-boundary as {@code
 * zone.PlannedInventoryClearing}. Mirrors that class's own raw Gold-Nugget-in-boxes payment
 * mechanism exactly (count-first via {@code zone.ContainerWithdraw}, no Yconomics economy/ledger call
 * -- only {@code bridge.YconomicsShopBridge#getListings} is used, purely to read real sell prices),
 * rather than inventing a second payment mechanism.
 *
 * <p>Buys from any OTHER plot in the same settlement with a real Shop listing for a plain item (tag/
 * exact-name listings are skipped -- a flagged v1 simplification, not an oversight; plain-item
 * listings are the overwhelming common case everywhere else in this mod) whose {@link
 * DataComponents#FOOD} component is present, one listing at a time, capped by whichever is smallest
 * of: the remaining nutrition-value deficit, the seller's real stock, the Guardhouse's own
 * Gold-Nugget balance (drawn from the Guardhouse PLOT's own regular boxes via {@link
 * Settlemynts#resolvePlotBoxes} -- NOT the Food Store container itself, which only ever holds food),
 * and the Food Store's own actual remaining room for that specific item (the "if enough room exists"
 * cap this class is the one place in this mod that needs at all -- {@code
 * zone.PlannedInventoryClearing}'s own buying has no analogous capacity cap to reuse).
 */
public final class GuardhouseFoodAutoBuy {

    private static final ShopResource GOLD_NUGGETS = ShopResource.ofItem(BuiltInRegistries.ITEM.getKey(Items.GOLD_NUGGET));

    private GuardhouseFoodAutoBuy() {
    }

    public static void buyForSettlement(ServerLevel level, GhostTownHallCoreEntity core) {
        for (PlotRecord plot : core.getPlots()) {
            if (!plot.zoneTypeId().equals(GuardhouseConstants.GUARDHOUSE_ZONE_TYPE_ID)) {
                continue;
            }
            GuardhouseIndex.get(level.getServer()).get(plot.plotId()).ifPresent(pos -> {
                ServerLevel guardhouseLevel = level.getServer().getLevel(pos.dimension());
                if (guardhouseLevel != null && guardhouseLevel.getBlockEntity(pos.pos()) instanceof GuardhouseBlockEntity guardhouse) {
                    buyForPlot(level, core, plot, guardhouse);
                }
            });
        }
    }

    private static void buyForPlot(ServerLevel level, GhostTownHallCoreEntity core, PlotRecord guardhousePlot, GuardhouseBlockEntity guardhouse) {
        int deficit = guardhouse.preferredFoodValue() - guardhouse.totalFoodValue();
        if (deficit <= 0) {
            return;
        }
        List<Container> paymentBoxes = Settlemynts.resolvePlotBoxes(level, guardhousePlot.plotId());
        for (PlotRecord sellerPlot : core.getPlots()) {
            if (deficit <= 0) {
                return;
            }
            if (sellerPlot.plotId().equals(guardhousePlot.plotId()) || sellerPlot.shopId().isEmpty()) {
                continue;
            }
            List<Container> sellerBoxes = Settlemynts.resolvePlotBoxes(level, sellerPlot.plotId());
            for (YconomicsShopBridge.ShopListingView listing : YconomicsShopBridge.getListings(level, sellerPlot.shopId().get())) {
                if (deficit <= 0) {
                    break;
                }
                deficit = buyOneListing(listing, deficit, sellerBoxes, paymentBoxes, guardhouse.foodContainer());
            }
        }
    }

    /** Returns the remaining deficit after attempting this one listing -- 0 or more units bought, or unchanged if this listing doesn't qualify at all. */
    private static int buyOneListing(YconomicsShopBridge.ShopListingView listing, int deficit,
                                      List<Container> sellerBoxes, List<Container> paymentBoxes, Container foodContainer) {
        if (listing.resource().itemId().isEmpty() || listing.pricePerUnit() <= 0) {
            return deficit;
        }
        Item item = BuiltInRegistries.ITEM.getOptional(listing.resource().itemId().get()).orElse(null);
        if (item == null) {
            return deficit;
        }
        FoodProperties food = new ItemStack(item).get(DataComponents.FOOD);
        if (food == null || food.nutrition() <= 0) {
            return deficit;
        }
        int perItemValue = food.nutrition();
        int unitsNeeded = (deficit + perItemValue - 1) / perItemValue;
        int sellerStock = ContainerWithdraw.countAvailable(sellerBoxes, listing.resource());
        int room = roomForItem(foodContainer, item);
        int buyerGold = ContainerWithdraw.countAvailable(paymentBoxes, GOLD_NUGGETS);
        int affordable = buyerGold / listing.pricePerUnit();
        int qty = Math.min(Math.min(unitsNeeded, sellerStock), Math.min(room, affordable));
        if (qty <= 0) {
            return deficit;
        }

        List<ItemStack> boughtFood = ContainerWithdraw.drain(sellerBoxes, listing.resource(), qty);
        int actuallyBought = boughtFood.stream().mapToInt(ItemStack::getCount).sum();
        if (actuallyBought <= 0) {
            return deficit;
        }
        List<ItemStack> payment = ContainerWithdraw.drain(paymentBoxes, GOLD_NUGGETS, actuallyBought * listing.pricePerUnit());
        for (ItemStack stack : payment) {
            ContainerDeposit.depositIntoAny(sellerBoxes, stack);
        }
        for (ItemStack stack : boughtFood) {
            ContainerDeposit.depositIntoAny(List.of(foodContainer), stack);
        }
        return deficit - actuallyBought * perItemValue;
    }

    private static int roomForItem(Container container, Item item) {
        int room = 0;
        int maxStackSize = new ItemStack(item).getMaxStackSize();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack existing = container.getItem(slot);
            if (existing.isEmpty()) {
                room += maxStackSize;
            } else if (existing.getItem() == item) {
                room += existing.getMaxStackSize() - existing.getCount();
            }
        }
        return room;
    }
}
