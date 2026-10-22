package com.github.cerealklla.settlemynts.watchtower;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The Watch Tower Beacon's own 1-slot fuel screen -- mirrors {@code guardhouse.GuardhouseFoodMenu}'s
 * shape exactly, just a single {@link WatchTowerFuelSlot} instead of 9 food slots.
 */
public class WatchTowerBeaconMenu extends AbstractContainerMenu {

    // Real report, 2026-10-22: "shift clicking into the container shows the inventory slots don't
    // line up with the visual areas for them." Root cause: the screen's own background used to blit
    // vanilla's `generic_54` chest texture's first *9-slot* row as decoration (copy-pasted from
    // guardhouse.GuardhouseFoodMenu/Screen, which is correct THERE because it really has 9 food
    // slots in a row) even though this menu only ever had 1 real slot -- the drawn boxes and the
    // real interactive slot never had anything to do with each other. The Screen now draws exactly
    // one slot sprite at this exact position instead of borrowing that mismatched 9-wide strip.
    public static final int FUEL_SLOT_X = 80;
    public static final int FUEL_SLOT_Y = 18;
    public static final int PLAYER_INV_Y = 110;

    private final Container fuelContainer;
    private final int litTimeRemaining;
    private final int litTotalTime;
    private final boolean manuallyEnabled;
    private final boolean canManage;
    private final BlockPos beaconPos;

    /** Server-side: a real Beacon backs this menu. */
    public WatchTowerBeaconMenu(MenuType<?> type, int containerId, Inventory inventory, Container fuelContainer,
                                 int litTimeRemaining, int litTotalTime, boolean manuallyEnabled, boolean canManage, BlockPos beaconPos) {
        super(type, containerId);
        this.fuelContainer = fuelContainer;
        this.litTimeRemaining = litTimeRemaining;
        this.litTotalTime = litTotalTime;
        this.manuallyEnabled = manuallyEnabled;
        this.canManage = canManage;
        this.beaconPos = beaconPos;
        layoutSlots(inventory);
    }

    /** Client-side reconstruction (see {@code registration.ModMenus}) -- extra fields arrive via the extra-data channel. */
    public WatchTowerBeaconMenu(MenuType<?> type, int containerId, Inventory inventory,
                                 int litTimeRemaining, int litTotalTime, boolean manuallyEnabled, boolean canManage, BlockPos beaconPos) {
        this(type, containerId, inventory, new SimpleContainer(1), litTimeRemaining, litTotalTime, manuallyEnabled, canManage, beaconPos);
    }

    public int litTimeRemaining() {
        return litTimeRemaining;
    }

    public int litTotalTime() {
        return litTotalTime;
    }

    public boolean manuallyEnabled() {
        return manuallyEnabled;
    }

    public boolean canManage() {
        return canManage;
    }

    public BlockPos beaconPos() {
        return beaconPos;
    }

    private void layoutSlots(Inventory inventory) {
        addSlot(new WatchTowerFuelSlot(fuelContainer, 0, FUEL_SLOT_X, FUEL_SLOT_Y));
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
            if (slotIndex == 0) {
                if (!moveItemStackTo(stack, 1, slots.size(), true)) {
                    return ItemStack.EMPTY;
                }
            } else if (!moveItemStackTo(stack, 0, 1, false)) {
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
        return fuelContainer.stillValid(player);
    }
}
