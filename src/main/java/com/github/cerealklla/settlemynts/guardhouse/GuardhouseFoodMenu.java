package com.github.cerealklla.settlemynts.guardhouse;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The Guardhouse's food Container screen -- real storage (unlike Blueprynts' funnel-style
 * Construction Box funding rows), restricted to food items via {@link GuardhouseFoodSlot#mayPlace}
 * on every one of its 9 slots. Not a plain vanilla {@code ChestMenu} -- real bug, 2026-09-30:
 * vanilla's own {@code Slot#mayPlace} defaults to {@code true} and never consults {@code
 * Container#canPlaceItem}, so a {@code ChestMenu} wrapping a food-restricted {@code Container}
 * silently let any item in regardless. {@link #quickMoveStack} mirrors {@code ChestMenu}'s own exact
 * shape (shift-click moves between the two slot groups, honoring {@code mayPlace} either way).
 */
public class GuardhouseFoodMenu extends AbstractContainerMenu {

    private static final int FOOD_SLOT_COUNT = 9;
    private static final int FOOD_SLOT_Y = 18;
    private static final int PLAYER_INV_Y = 84;

    private final Container foodContainer;

    /** Server-side: a real Guardhouse backs this menu. */
    public GuardhouseFoodMenu(MenuType<?> type, int containerId, Inventory inventory, Container foodContainer) {
        super(type, containerId);
        this.foodContainer = foodContainer;
        layoutSlots(inventory);
    }

    /** Client-side reconstruction (see {@code registration.ModMenus}) -- no real Guardhouse to read from. */
    public GuardhouseFoodMenu(MenuType<?> type, int containerId, Inventory inventory) {
        this(type, containerId, inventory, new SimpleContainer(FOOD_SLOT_COUNT));
    }

    private void layoutSlots(Inventory inventory) {
        for (int i = 0; i < FOOD_SLOT_COUNT; i++) {
            addSlot(new GuardhouseFoodSlot(foodContainer, i, 8 + i * 18, FOOD_SLOT_Y));
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, PLAYER_INV_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 8 + col * 18, PLAYER_INV_Y + 58));
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        ItemStack clicked = ItemStack.EMPTY;
        Slot slot = slots.get(slotIndex);
        if (slot != null && slot.hasItem()) {
            ItemStack stack = slot.getItem();
            clicked = stack.copy();
            if (slotIndex < FOOD_SLOT_COUNT) {
                if (!moveItemStackTo(stack, FOOD_SLOT_COUNT, slots.size(), true)) {
                    return ItemStack.EMPTY;
                }
            } else if (!moveItemStackTo(stack, 0, FOOD_SLOT_COUNT, false)) {
                return ItemStack.EMPTY;
            }

            if (stack.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
        }
        return clicked;
    }

    @Override
    public boolean stillValid(Player player) {
        return foodContainer.stillValid(player);
    }
}
