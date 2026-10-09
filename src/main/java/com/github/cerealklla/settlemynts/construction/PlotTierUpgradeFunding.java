package com.github.cerealklla.settlemynts.construction;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.github.cerealklla.blueprynts.blueprint.GenericResource;
import com.github.cerealklla.settlemynts.api.Settlemynts;
import com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge;
import com.github.cerealklla.settlemynts.zone.PlotRecord;
import com.github.cerealklla.settlemynts.zone.ShopResource;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/**
 * Funds raising a plot's own unlocked construction-Tier cap by one (added 2026-10-09, explicit
 * request: "same options as upgrading structures" -- mirrors Lyfe's {@code
 * structure.StructureUpgradeFunding} exactly, three funding options, just against {@link
 * PlotTierUpgradeCost} instead of that class's {@code StructureUpgradeCost}, and native to
 * Settlemynts since the plot/shop primitives already live here (no bridge needed).
 *
 * <p>Deliberately separate from the Construction Box's own existing funding (deposit resources into
 * the box to build a specific Blueprint) -- see {@code zone.PlotRecord#tier}'s own class doc. This
 * class never touches a Construction Box at all; it only raises {@code PlotRecord#tier} and debits
 * the plot's own boxes/the player's gold.
 */
public final class PlotTierUpgradeFunding {

    public enum FundingOption { ON_HAND, MIX, GOLD_ONLY }

    public record Result(boolean success, String message) {
        static Result ok() {
            return new Result(true, null);
        }

        static Result fail(String message) {
            return new Result(false, message);
        }
    }

    private record Need(ResourceCost entry, int onHand, int shortfall) {
    }

    private PlotTierUpgradeFunding() {
    }

    public static Result fund(ServerPlayer player, ServerLevel level, PlotRecord plot, UUID settlementCoreId, BlockPos originPos, FundingOption option) {
        List<ResourceCost> cost = PlotTierUpgradeCost.costFor(plot.tier() + 1);
        List<Container> plotBoxes = option == FundingOption.GOLD_ONLY ? List.of() : Settlemynts.resolvePlotBoxes(level, plot.plotId());

        List<Need> needs = new ArrayList<>();
        for (ResourceCost entry : cost) {
            int available = plotBoxes.isEmpty() ? 0 : countAvailable(plotBoxes, entry.resource());
            int onHand = Math.min(available, entry.amount());
            needs.add(new Need(entry, onHand, entry.amount() - onHand));
        }

        if (option == FundingOption.ON_HAND) {
            for (Need need : needs) {
                if (need.shortfall() > 0) {
                    return Result.fail("Missing " + need.shortfall() + " more " + need.entry().resource().label() + " on this plot.");
                }
            }
            for (Need need : needs) {
                drain(plotBoxes, need.entry().resource(), need.entry().amount());
            }
            return Result.ok();
        }

        List<PlotTierMarketPurchasing.Purchase> purchases = new ArrayList<>();
        int totalCharge = 0;
        for (Need need : needs) {
            int quantity = option == FundingOption.GOLD_ONLY ? need.entry().amount() : need.shortfall();
            if (quantity <= 0) {
                continue;
            }
            PlotTierMarketPurchasing.Purchase purchase = PlotTierMarketPurchasing.resolvePurchase(level, settlementCoreId, originPos, need.entry(), quantity);
            if (purchase == null) {
                return Result.fail("Nobody sells " + need.entry().resource().label() + " nearby.");
            }
            purchases.add(purchase);
            totalCharge += purchase.buyerCharge();
        }

        if (YconomicsShopBridge.getNuggetBalance(player) < totalCharge) {
            return Result.fail("You need " + totalCharge + " Gold Nuggets for the remaining resources.");
        }

        if (option == FundingOption.MIX) {
            for (Need need : needs) {
                if (need.onHand() > 0) {
                    drain(plotBoxes, need.entry().resource(), need.onHand());
                }
            }
        }
        for (PlotTierMarketPurchasing.Purchase purchase : purchases) {
            ShopResource resource = ShopResource.ofTag(purchase.entry().resource().tag());
            List<Container> sellerBoxes = Settlemynts.resolvePlotBoxes(level, purchase.sellerPlotId());
            Settlemynts.purchaseFromSettlementShop(level, purchase.sellerPlotId(), resource, purchase.quantity(), sellerBoxes);
        }
        YconomicsShopBridge.withdrawNuggets(player, totalCharge);
        return Result.ok();
    }

    private static int countAvailable(List<Container> boxes, GenericResource resource) {
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

    private static void drain(List<Container> boxes, GenericResource resource, int amount) {
        int remaining = amount;
        for (Container box : boxes) {
            for (int slot = 0; slot < box.getContainerSize() && remaining > 0; slot++) {
                ItemStack stack = box.getItem(slot);
                if (stack.isEmpty() || !resource.matches(stack.getItem())) {
                    continue;
                }
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                remaining -= take;
            }
        }
    }
}
