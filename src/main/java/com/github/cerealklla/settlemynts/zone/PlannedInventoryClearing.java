package com.github.cerealklla.settlemynts.zone;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.github.cerealklla.settlemynts.api.Settlemynts;
import com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Automated inter-plot shop trading at midnight (design doc Section 14a, added 2026-10-09, explicit
 * user request) -- a plot below its own "happy state" (see {@link PlannedInventoryTarget}) buys from
 * another plot in the same settlement that's currently above its own happy state for that resource,
 * paying the seller's own configured Shop sell price. Called once per settlement group by {@code
 * ShopMidnightRestockTicker} right after its existing restock pass.
 *
 * <p><b>Deliberately takes a plain {@code List<PlotRecord>}</b>, not "today's settlement" -- this is
 * the explicitly-planned foundation for a later traveling-merchant feature (not built yet): a trader
 * arriving in a settlement would run this exact same method against the settlement's real plot list
 * plus one more synthetic {@code PlotRecord}-shaped participant representing the merchant's own
 * stock/wants, with no signature change needed here.
 *
 * <p>Pricing is never decided here -- a plot with surplus but no real Shop listing price for that
 * resource is simply skipped this round (confirmed default, 2026-10-09): Planned Inventory only ever
 * decides quantities, the existing Shop system remains the sole source of price.
 */
public final class PlannedInventoryClearing {

    private PlannedInventoryClearing() {
    }

    private static final ShopResource GOLD_NUGGETS = ShopResource.ofItem(BuiltInRegistries.ITEM.getKey(Items.GOLD_NUGGET));

    private record Participant(PlotRecord plot, List<Container> boxes) {
    }

    public static void settleGroup(ServerLevel level, List<PlotRecord> plots) {
        List<Participant> participants = new ArrayList<>();
        for (PlotRecord plot : plots) {
            if (plot.shopId().isEmpty() || plot.plannedInventory().isEmpty()) {
                continue;
            }
            participants.add(new Participant(plot, Settlemynts.resolvePlotBoxes(level, plot.plotId())));
        }
        if (participants.size() < 2) {
            return; // Need at least one potential buyer and one potential seller.
        }

        Set<ResourceKey> resourceKeys = new LinkedHashSet<>();
        for (Participant p : participants) {
            for (PlannedInventoryTarget target : p.plot().plannedInventory()) {
                resourceKeys.add(new ResourceKey(target.resourceKey(), target.isTag()));
            }
        }

        for (ResourceKey key : resourceKeys) {
            settleResource(level, participants, key);
        }
    }

    private record ResourceKey(Identifier id, boolean isTag) {
        ShopResource toShopResource() {
            return isTag ? ShopResource.ofTag(TagKey.create(net.minecraft.core.registries.Registries.ITEM, id)) : ShopResource.ofItem(id);
        }
    }

    private record Buyer(Participant participant, int remainingDeficit) {
    }

    private record Seller(Participant participant, int remainingSurplus, int pricePerUnit) {
    }

    private static void settleResource(ServerLevel level, List<Participant> participants, ResourceKey key) {
        ShopResource resource = key.toShopResource();
        List<Buyer> buyers = new ArrayList<>();
        List<Seller> sellers = new ArrayList<>();

        for (Participant p : participants) {
            PlannedInventoryTarget target = findTarget(p.plot(), key);
            if (target == null) {
                continue;
            }
            int currentStock = ContainerWithdraw.countAvailable(p.boxes(), resource);
            if (currentStock < target.targetCount()) {
                buyers.add(new Buyer(p, target.targetCount() - currentStock));
            } else if (currentStock > target.targetCount()) {
                int pricePerUnit = sellPriceFor(level, p.plot().shopId().orElseThrow(), resource);
                if (pricePerUnit > 0) {
                    sellers.add(new Seller(p, currentStock - target.targetCount(), pricePerUnit));
                }
            }
        }

        for (int bi = 0; bi < buyers.size(); bi++) {
            Buyer buyer = buyers.get(bi);
            int remainingDeficit = buyer.remainingDeficit();
            if (remainingDeficit <= 0) {
                continue;
            }
            for (int si = 0; si < sellers.size() && remainingDeficit > 0; si++) {
                Seller seller = sellers.get(si);
                if (seller.remainingSurplus() <= 0 || seller.participant() == buyer.participant()) {
                    continue;
                }
                int buyerGold = ContainerWithdraw.countAvailable(buyer.participant().boxes(), GOLD_NUGGETS);
                int affordable = buyerGold / seller.pricePerUnit();
                int units = Math.min(remainingDeficit, Math.min(seller.remainingSurplus(), affordable));
                if (units <= 0) {
                    continue;
                }
                List<ItemStack> goods = ContainerWithdraw.drain(seller.participant().boxes(), resource, units);
                for (ItemStack stack : goods) {
                    ContainerDeposit.depositIntoAny(buyer.participant().boxes(), stack);
                }
                int cost = units * seller.pricePerUnit();
                List<ItemStack> payment = ContainerWithdraw.drain(buyer.participant().boxes(), GOLD_NUGGETS, cost);
                for (ItemStack stack : payment) {
                    ContainerDeposit.depositIntoAny(seller.participant().boxes(), stack);
                }
                remainingDeficit -= units;
                sellers.set(si, new Seller(seller.participant(), seller.remainingSurplus() - units, seller.pricePerUnit()));
            }
            buyers.set(bi, new Buyer(buyer.participant(), remainingDeficit));
        }
    }

    private static PlannedInventoryTarget findTarget(PlotRecord plot, ResourceKey key) {
        for (PlannedInventoryTarget target : plot.plannedInventory()) {
            if (target.isTag() == key.isTag() && target.resourceKey().equals(key.id())) {
                return target;
            }
        }
        return null;
    }

    private static int sellPriceFor(ServerLevel level, UUID shopId, ShopResource resource) {
        for (YconomicsShopBridge.ShopListingView view : YconomicsShopBridge.getListings(level, shopId)) {
            if (sameResource(view.resource(), resource)) {
                return view.pricePerUnit();
            }
        }
        return 0;
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
