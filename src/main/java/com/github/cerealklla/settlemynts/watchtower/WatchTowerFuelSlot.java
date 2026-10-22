package com.github.cerealklla.settlemynts.watchtower;

import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;

/**
 * The Beacon's single fuel slot -- real enforcement point for "Coal/Charcoal only," same reasoning
 * as {@code guardhouse.GuardhouseFoodSlot}'s own doc (vanilla's {@code Slot#mayPlace} defaults to
 * {@code true} and never consults {@code Container#canPlaceItem} on its own).
 */
public class WatchTowerFuelSlot extends Slot {

    public WatchTowerFuelSlot(Container container, int index, int x, int y) {
        super(container, index, x, y);
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return stack.is(Items.COAL) || stack.is(Items.CHARCOAL);
    }
}
