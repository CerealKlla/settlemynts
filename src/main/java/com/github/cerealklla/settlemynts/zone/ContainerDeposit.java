package com.github.cerealklla.settlemynts.zone;

import java.util.List;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/**
 * Deposits an {@link ItemStack} into whichever of a plot's boxes has room, splitting across several
 * boxes if one isn't enough -- lifted out of {@code ShopSeeding}'s own original private method
 * (2026-10-05) so the new Lumberjack/Farmer worker NPCs can deposit harvested yield the same way
 * Shop seeding deposits stock, without duplicating this small loop a third time.
 */
public final class ContainerDeposit {

    private ContainerDeposit() {
    }

    public static void depositIntoAny(List<Container> boxes, ItemStack stack) {
        for (Container box : boxes) {
            if (stack.isEmpty()) {
                return;
            }
            for (int slot = 0; slot < box.getContainerSize() && !stack.isEmpty(); slot++) {
                ItemStack existing = box.getItem(slot);
                if (existing.isEmpty()) {
                    int placed = Math.min(stack.getCount(), stack.getMaxStackSize());
                    box.setItem(slot, stack.split(placed));
                } else if (ItemStack.isSameItemSameComponents(existing, stack)) {
                    int room = existing.getMaxStackSize() - existing.getCount();
                    if (room > 0) {
                        int moved = Math.min(room, stack.getCount());
                        existing.grow(moved);
                        stack.shrink(moved);
                    }
                }
            }
        }
    }
}
