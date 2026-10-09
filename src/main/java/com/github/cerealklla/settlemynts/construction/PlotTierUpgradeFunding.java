package com.github.cerealklla.settlemynts.construction;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.github.cerealklla.blueprynts.blueprint.GenericResource;
import com.github.cerealklla.settlemynts.api.Settlemynts;
import com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.zone.PlotRecord;
import com.github.cerealklla.settlemynts.zone.ShopResource;
import com.github.cerealklla.settlemynts.zone.ZoneType;
import com.github.cerealklla.settlemynts.zone.ZoneTypeRegistry;
import com.github.cerealklla.settlemynts.zone.ZoneTypeUnlocks;

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
 *
 * <p><b>Widened 2026-10-09, same day</b> -- both {@link #preview} (shown on {@code
 * client.UpgradePlotScreen} before any button is clicked) and {@link #fund} (the actual charge) now
 * go through the same {@link PlotTierMarketPurchasing#planPurchase} cheapest-first, stock-aware
 * allocation, so the number the player sees is exactly what they'll actually be charged -- real
 * follow-up request after the first live test showed a flat, unexplained "missing resources"
 * failure with no visibility into why.
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

    /** One resource row for {@code client.UpgradePlotScreen} -- {@code mixCost}/{@code goldCost} are each that option's own total charge for JUST this resource, {@code mixFullyCovered}/{@code goldFullyCovered} say whether the whole settlement economy (plot stock + every seller) can actually supply the full needed amount at all. */
    public record ResourcePreviewEntry(String label, int amount, int onHand, int mixCost, boolean mixFullyCovered, int goldCost, boolean goldFullyCovered) {
    }

    /**
     * {@code onHandEnabled}/{@code mixEnabled}/{@code goldEnabled} already fold in both "can the
     * resources actually be sourced at all" and "can the player actually afford the gold involved" --
     * {@code client.UpgradePlotScreen} just disables a button directly off these, no further
     * client-side math needed. {@code goldOnPlot}/{@code goldOnPerson} (added 2026-10-09, explicit
     * request) are purely informational -- real Gold Nuggets don't count toward either funding option
     * today (only the player's own balance is ever charged), this just answers "how much is sitting
     * in my plot's own boxes vs. on me." {@code newlyAllowedZoneTypeLabels} (added 2026-10-09,
     * explicit request: "I'd also like to see 'Buildings Allowed After Upgrade'... listing what the
     * next upgrade would allow a player to place") -- empty unless this upgrade is for the
     * settlement's own Town Hall plot specifically, since raising any other plot's own Tier cap
     * doesn't change which Zone Types the settlement can establish (see {@code zone.ZoneTypeUnlocks}'s
     * own Town Hall-Tier gate) -- a Mayor-skill-level unlock (the other, independent gate) isn't shown
     * here, it isn't caused by this specific upgrade action.
     */
    public record Preview(List<ResourcePreviewEntry> resources, boolean onHandEnabled, int mixTotalCost, boolean mixEnabled,
                           int goldTotalCost, boolean goldEnabled, int goldOnPlot, int goldOnPerson,
                           List<String> newlyAllowedZoneTypeLabels) {
    }

    private record Need(ResourceCost entry, int onHand, int shortfall) {
    }

    private PlotTierUpgradeFunding() {
    }

    public static Preview preview(ServerPlayer player, ServerLevel level, PlotRecord plot, UUID settlementCoreId, BlockPos originPos) {
        List<ResourceCost> cost = PlotTierUpgradeCost.costFor(plot.tier() + 1);
        List<Container> plotBoxes = Settlemynts.resolvePlotBoxes(level, plot.plotId());

        List<ResourcePreviewEntry> entries = new ArrayList<>();
        boolean onHandEnabled = true;
        int mixTotal = 0;
        boolean mixEnabled = true;
        int goldTotal = 0;
        boolean goldEnabled = true;
        for (ResourceCost entry : cost) {
            int onHand = Math.min(countAvailable(plotBoxes, entry.resource()), entry.amount());
            int shortfall = entry.amount() - onHand;
            if (shortfall > 0) {
                onHandEnabled = false;
            }
            PlotTierMarketPurchasing.Allocation mixAlloc = shortfall > 0
                    ? PlotTierMarketPurchasing.planPurchase(level, settlementCoreId, originPos, entry, shortfall)
                    : new PlotTierMarketPurchasing.Allocation(List.of(), 0, 0);
            boolean mixCovered = onHand + mixAlloc.quantityFilled() >= entry.amount();
            mixTotal += mixAlloc.totalCost();
            if (!mixCovered) {
                mixEnabled = false;
            }
            PlotTierMarketPurchasing.Allocation goldAlloc = PlotTierMarketPurchasing.planPurchase(level, settlementCoreId, originPos, entry, entry.amount());
            boolean goldCovered = goldAlloc.fullyCovered(entry.amount());
            goldTotal += goldAlloc.totalCost();
            if (!goldCovered) {
                goldEnabled = false;
            }
            entries.add(new ResourcePreviewEntry(entry.resource().label(), entry.amount(), onHand, mixAlloc.totalCost(), mixCovered, goldAlloc.totalCost(), goldCovered));
        }
        int nuggetBalance = YconomicsShopBridge.getNuggetBalance(player);
        if (mixEnabled && nuggetBalance < mixTotal) {
            mixEnabled = false;
        }
        if (goldEnabled && nuggetBalance < goldTotal) {
            goldEnabled = false;
        }
        int goldOnPlot = countAvailable(plotBoxes, net.minecraft.world.item.Items.GOLD_NUGGET);

        int nextTier = plot.tier() + 1;
        List<String> newlyAllowedZoneTypeLabels = new ArrayList<>();
        if (plot.zoneTypeId().equals(GhostTownHallCoreEntity.TOWN_HALL_ZONE_TYPE_ID)) {
            for (ZoneType type : ZoneTypeRegistry.all()) {
                if (ZoneTypeUnlocks.minTownHallTierForZoneType(type.id()) == nextTier) {
                    newlyAllowedZoneTypeLabels.add(type.label());
                }
            }
        }

        return new Preview(entries, onHandEnabled, mixTotal, mixEnabled, goldTotal, goldEnabled, goldOnPlot, nuggetBalance, newlyAllowedZoneTypeLabels);
    }

    private static int countAvailable(List<Container> boxes, net.minecraft.world.item.Item item) {
        int total = 0;
        for (Container box : boxes) {
            for (int slot = 0; slot < box.getContainerSize(); slot++) {
                ItemStack stack = box.getItem(slot);
                if (stack.getItem() == item) {
                    total += stack.getCount();
                }
            }
        }
        return total;
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

        List<PlotTierMarketPurchasing.Allocation> allocations = new ArrayList<>();
        int totalCharge = 0;
        for (Need need : needs) {
            int quantity = option == FundingOption.GOLD_ONLY ? need.entry().amount() : need.shortfall();
            if (quantity <= 0) {
                allocations.add(new PlotTierMarketPurchasing.Allocation(List.of(), 0, 0));
                continue;
            }
            PlotTierMarketPurchasing.Allocation allocation = PlotTierMarketPurchasing.planPurchase(level, settlementCoreId, originPos, need.entry(), quantity);
            if (!allocation.fullyCovered(quantity)) {
                return Result.fail("Not enough " + need.entry().resource().label() + " available in the settlement.");
            }
            allocations.add(allocation);
            totalCharge += allocation.totalCost();
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
        for (PlotTierMarketPurchasing.Allocation allocation : allocations) {
            for (PlotTierMarketPurchasing.Purchase purchase : allocation.purchases()) {
                ShopResource resource = ShopResource.ofTag(purchase.entry().resource().tag());
                List<Container> sellerBoxes = Settlemynts.resolvePlotBoxes(level, purchase.sellerPlotId());
                Settlemynts.purchaseFromSettlementShop(level, purchase.sellerPlotId(), resource, purchase.quantity(), sellerBoxes);
            }
        }
        YconomicsShopBridge.withdrawNuggets(player, totalCharge);
        return Result.ok();
    }

    /** Shared with {@link PlotTierMarketPurchasing}'s own identical scan (package-private there). */
    private static int countAvailable(List<Container> boxes, GenericResource resource) {
        return PlotTierMarketPurchasing.countAvailable(boxes, resource);
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
