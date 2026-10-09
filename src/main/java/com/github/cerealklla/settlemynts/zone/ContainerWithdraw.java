package com.github.cerealklla.settlemynts.zone;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/**
 * Withdraw-side sibling of {@link ContainerDeposit} -- added 2026-10-09 for {@link
 * PlannedInventoryClearing}'s own needs (pulling a seller's surplus goods, and a buyer's Gold Nugget
 * payment, out of a plot's boxes). A near-identical port of Yconomics' {@code
 * shop.ShopTransferEngine#countAvailable}/{@code #drain}, reimplemented here against Settlemynts' own
 * local {@link ShopResource} instead of importing Yconomics' type directly -- keeps this suite's
 * established "a Yconomics type never appears in a signature outside a bridge class" convention intact
 * (see {@link ShopResource}'s own class doc).
 */
public final class ContainerWithdraw {

    private ContainerWithdraw() {
    }

    /** How many units of {@code resource} are available across all of {@code containers}, in total. */
    public static int countAvailable(List<Container> containers, ShopResource resource) {
        int total = 0;
        for (Container container : containers) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (resource.matches(stack)) {
                    total += stack.getCount();
                }
            }
        }
        return total;
    }

    /**
     * Removes up to {@code amount} units of {@code resource} from {@code containers} (list order).
     * Returns the actual stacks removed (not just a count) -- a tag-backed resource can match several
     * different concrete items, and the caller needs to know exactly which one(s) to re-deposit
     * elsewhere.
     */
    public static List<ItemStack> drain(List<Container> containers, ShopResource resource, int amount) {
        List<ItemStack> taken = new ArrayList<>();
        int remaining = amount;
        for (Container container : containers) {
            if (remaining <= 0) {
                break;
            }
            for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
                ItemStack stack = container.getItem(slot);
                if (!resource.matches(stack)) {
                    continue;
                }
                int take = Math.min(remaining, stack.getCount());
                taken.add(stack.copyWithCount(take));
                stack.shrink(take);
                remaining -= take;
            }
        }
        return taken;
    }
}
