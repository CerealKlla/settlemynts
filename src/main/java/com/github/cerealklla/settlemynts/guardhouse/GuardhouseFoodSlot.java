package com.github.cerealklla.settlemynts.guardhouse;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * One food-storage slot of a {@link GuardhouseFoodMenu} -- real storage, unlike Blueprynts'
 * funnel-style {@code ConstructionBoxFundingSlot} (items sit here, they don't tally into anything
 * and clear). {@link #mayPlace} is the actual enforcement point for "food only": vanilla's own
 * {@code Slot#mayPlace} defaults to {@code true} unconditionally and never consults {@code
 * Container#canPlaceItem} on its own (confirmed real bug, 2026-09-30 -- the food {@link Container}'s
 * own {@code canPlaceItem} override was silently never called while this block reused vanilla's
 * plain {@code ChestMenu}, so non-food items were accepted). A plain {@code Container#canPlaceItem}
 * override alone is not enough with real vanilla {@code Slot}s -- this dedicated Slot subclass is
 * the fix.
 */
public class GuardhouseFoodSlot extends Slot {

    public GuardhouseFoodSlot(Container container, int index, int x, int y) {
        super(container, index, x, y);
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return stack.get(DataComponents.FOOD) != null;
    }
}
