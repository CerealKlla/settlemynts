package com.github.cerealklla.settlemynts.guardhouse;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.github.cerealklla.settlemynts.api.Settlemynts;
import com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.zone.ContainerDeposit;
import com.github.cerealklla.settlemynts.zone.ContainerWithdraw;
import com.github.cerealklla.settlemynts.zone.MidnightWorkQueue;
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
 * <p>Buys from any OTHER plot in the same settlement with a real Shop listing for a concrete item
 * (a tag-only listing is skipped -- {@code listing.resource().itemId().isEmpty()} below -- since a
 * tag has no single real item to check {@link DataComponents#FOOD} against). The real {@link
 * FoodProperties} check is read off a genuine sample stack pulled from the seller's own boxes
 * (real report, 2026-10-17: a bare reconstructed stack of the item type alone has no FOOD component
 * at all for Lyfe's own crafted/quality-scaled dishes, whose real nutrition is baked onto each
 * individual stack at crafting time, not registered statically on the base item -- every such
 * listing, the overwhelming majority of what a real food shop sells, was silently skipped outright
 * until this was fixed), one listing at a time, capped by whichever is smallest
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

    /**
     * Enqueues this settlement's food-buying work onto {@link MidnightWorkQueue} instead of running
     * it all synchronously -- real production incident, 2026-10-21 (see {@code
     * zone.PlannedInventoryClearing}'s own decisions.md entries for the full three-round writeup of
     * this exact failure mode elsewhere in the same midnight pipeline): this method's own
     * (guardhouse x other-plot x listing) nested loop had never been chunked, and while no single
     * incident was ever pinned on it specifically, it's the same unbounded shape that already caused
     * three real freezes/crashes elsewhere -- fixed proactively rather than waiting for a fourth. One
     * job per (guardhouse, seller plot) pair; each job's own inner listing loop is small and bounded
     * (a single shop's real listing count), so it doesn't need further splitting.
     */
    public static void enqueueForSettlement(ServerLevel level, GhostTownHallCoreEntity core, MidnightWorkQueue queue) {
        // Real lag report, 2026-10-20: this settlement's own GuardhouseFoodAutoBuy jobs were each
        // taking 67-84ms, back to back -- traced to buyFromSellerPlot calling resolvePlotBoxes (a
        // full-world GhostTownHallCoreEntity scan, see that method's own doc) fresh on every single
        // job, once per (guardhouse, seller) pair. With more than one Guardhouse in a settlement, the
        // exact same seller plot's boxes got re-scanned once per guardhouse, redundantly -- the same
        // "resolve once, reuse" gap already fixed for zone.PlotCraftingTicker and
        // PlannedInventoryClearing#executeCraftOne, just never applied here. Resolved once per
        // settlement instead, for every seller-eligible plot (has a real Shop), shared across every
        // guardhouse's own per-seller loop below.
        Map<UUID, List<Container>> sellerBoxesByPlotId = new java.util.HashMap<>();
        for (PlotRecord plot : core.getPlots()) {
            if (plot.shopId().isPresent()) {
                sellerBoxesByPlotId.put(plot.plotId(), Settlemynts.resolvePlotBoxes(level, plot.plotId()));
            }
        }
        for (PlotRecord plot : core.getPlots()) {
            if (!plot.zoneTypeId().equals(GuardhouseConstants.GUARDHOUSE_ZONE_TYPE_ID)) {
                continue;
            }
            queue.enqueue(() -> GuardhouseIndex.get(level.getServer()).get(plot.plotId()).ifPresent(pos -> {
                ServerLevel guardhouseLevel = level.getServer().getLevel(pos.dimension());
                if (guardhouseLevel != null && guardhouseLevel.getBlockEntity(pos.pos()) instanceof GuardhouseBlockEntity guardhouse) {
                    enqueueBuyForPlot(level, core, plot, guardhouse, queue, sellerBoxesByPlotId);
                }
            }));
        }
    }

    private static void enqueueBuyForPlot(ServerLevel level, GhostTownHallCoreEntity core, PlotRecord guardhousePlot,
                                           GuardhouseBlockEntity guardhouse, MidnightWorkQueue queue,
                                           Map<UUID, List<Container>> sellerBoxesByPlotId) {
        List<Container> paymentBoxes = Settlemynts.resolvePlotBoxes(level, guardhousePlot.plotId());
        for (PlotRecord sellerPlot : core.getPlots()) {
            if (sellerPlot.plotId().equals(guardhousePlot.plotId()) || sellerPlot.shopId().isEmpty()) {
                continue;
            }
            List<Container> sellerBoxes = sellerBoxesByPlotId.get(sellerPlot.plotId());
            queue.enqueue(() -> buyFromSellerPlot(level, guardhouse, paymentBoxes, sellerPlot, sellerBoxes));
        }
    }

    private static void buyFromSellerPlot(ServerLevel level, GuardhouseBlockEntity guardhouse, List<Container> paymentBoxes,
                                           PlotRecord sellerPlot, List<Container> sellerBoxes) {
        int deficit = guardhouse.preferredFoodValue() - guardhouse.totalFoodValue();
        if (deficit <= 0) {
            return;
        }
        for (YconomicsShopBridge.ShopListingView listing : YconomicsShopBridge.getListings(level, sellerPlot.shopId().get())) {
            if (deficit <= 0) {
                break;
            }
            deficit = buyOneListing(listing, deficit, sellerBoxes, paymentBoxes, guardhouse.foodContainer());
        }
    }

    /** Returns the remaining deficit after attempting this one listing -- 0 or more units bought, or unchanged if this listing doesn't qualify at all. */
    private static int buyOneListing(YconomicsShopBridge.ShopListingView listing, int deficit,
                                      List<Container> sellerBoxes, List<Container> paymentBoxes, Container foodContainer) {
        if (listing.resource().itemId().isEmpty() || listing.pricePerUnit() <= 0) {
            return deficit;
        }
        // Real report, 2026-10-17: "I don't see guardhouses buying food that I have listed for sale
        // on my private residence plot." Root cause: this used to build a bare `new ItemStack(item)`
        // from just the registered item type to read its FoodProperties -- correct for a plain
        // vanilla food item, but Lyfe's own crafted/quality-scaled dishes (cook.CookingListener
        // #bakeIcons) never register a static FoodProperties on the base item at all; the real
        // nutrition is baked onto each individual crafted stack as a per-stack component override at
        // crafting time. A bare reconstructed stack of such an item has no FOOD component whatsoever,
        // so every one of these listings -- the overwhelming majority of what a real player-run food
        // shop actually sells -- was silently skipped outright. Fixed by reading a real sample stack
        // out of the seller's own boxes instead of reconstructing a blank one.
        ItemStack sample = findSample(sellerBoxes, listing.resource());
        if (sample.isEmpty()) {
            return deficit;
        }
        Item item = sample.getItem();
        FoodProperties food = sample.get(DataComponents.FOOD);
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

    /** First real stack in {@code boxes} matching {@code resource} -- used so a per-stack component (like Lyfe's baked crafted-food nutrition) is read from an actual stack, never a blank reconstruction. */
    private static ItemStack findSample(List<Container> boxes, ShopResource resource) {
        for (Container box : boxes) {
            for (int slot = 0; slot < box.getContainerSize(); slot++) {
                ItemStack stack = box.getItem(slot);
                if (!stack.isEmpty() && resource.matches(stack)) {
                    return stack;
                }
            }
        }
        return ItemStack.EMPTY;
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
