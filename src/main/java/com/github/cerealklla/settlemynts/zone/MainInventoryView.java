package com.github.cerealklla.settlemynts.zone;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * A {@link Container} view over a player's main inventory only (vanilla {@link Inventory} slots
 * 9-35), excluding the hotbar -- lets {@link ContainerWithdraw} operate on "everything except
 * what's equipped/in the hotbar" without that class needing any slot-range concept of its own.
 * Mirrors {@code AutoDropper}'s own {@code MAIN_INVENTORY_START}/{@code MAIN_INVENTORY_END}
 * constants. Added 2026-10-24 for {@code SettlemyntsMod#autoSellToShopCart} (explicit user request:
 * "Auto Sell should never take items which are equipped, including hotbar" -- the hotbar was being
 * swept too, since {@link ContainerWithdraw#drain} was handed the player's raw {@link Inventory}
 * directly, and vanilla's {@code Container#getItem}/{@code #removeItem} on that object cover all 36
 * hotbar+main slots as one range).
 *
 * <p>{@link #getItem} returns the same live {@link ItemStack} reference {@link Inventory} itself
 * holds (not a copy) -- {@link ContainerWithdraw#drain}'s {@code stack.shrink(take)} mutates it in
 * place, same as every other real {@code Container} it's used against.
 */
public final class MainInventoryView implements Container {

    private static final int OFFSET = 9;
    private static final int SIZE = 27;

    private final Inventory inventory;

    public MainInventoryView(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public int getContainerSize() {
        return SIZE;
    }

    @Override
    public boolean isEmpty() {
        for (int i = 0; i < SIZE; i++) {
            if (!inventory.getItem(OFFSET + i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return inventory.getItem(OFFSET + slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        return inventory.removeItem(OFFSET + slot, amount);
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return inventory.removeItemNoUpdate(OFFSET + slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        inventory.setItem(OFFSET + slot, stack);
    }

    @Override
    public void setChanged() {
        inventory.setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return inventory.stillValid(player);
    }

    @Override
    public void clearContent() {
        for (int i = 0; i < SIZE; i++) {
            inventory.setItem(OFFSET + i, ItemStack.EMPTY);
        }
    }
}
