package com.github.cerealklla.settlemynts.zone;

import java.util.List;
import java.util.Map;

import com.github.cerealklla.settlemynts.api.Settlemynts;
import com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * "Auto-Drop" keybind (2026-10-17, see {@code RequestAutoDropPayload}) -- standing on a plot you
 * own, sweep every main-inventory item (not the hotbar, not equipped/offhand -- vanilla {@link
 * Inventory} slots 9-35 only) that the plot's boxes already stock into those boxes. Only moves an
 * item whose id already appears somewhere in the plot's own box stock ({@link
 * Settlemynts#scanItemStock}) -- this is a tidy-up for restocking a shop/crafting plot, not a
 * general "dump my inventory" dump into boxes that were never meant to hold that item. The Coin
 * Purse is excluded outright (checked via {@link YconomicsShopBridge}, only when Yconomics is
 * actually loaded -- no Coin Purse item exists otherwise).
 */
public final class AutoDropper {

    private static final int MAIN_INVENTORY_START = 9;
    private static final int MAIN_INVENTORY_END = 36; // exclusive

    private AutoDropper() {
    }

    public static void autoDrop(ServerPlayer player, ServerLevel level) {
        var plot = Settlemynts.findPlotAt(level, player.blockPosition());
        if (plot.isEmpty()) {
            player.sendSystemMessage(Component.literal("You must be standing on a plot to Auto-Drop."));
            return;
        }
        if (plot.get().owner().isEmpty() || !plot.get().owner().get().equals(player.getUUID())) {
            player.sendSystemMessage(Component.literal("You don't own this plot."));
            return;
        }
        List<Container> boxes = Settlemynts.resolvePlotBoxes(level, plot.get().plotId());
        if (boxes.isEmpty()) {
            player.sendSystemMessage(Component.literal("This plot has no boxes to drop items into."));
            return;
        }
        Map<Identifier, Integer> stock = Settlemynts.scanItemStock(boxes);
        if (stock.isEmpty()) {
            player.sendSystemMessage(Component.literal("This plot's boxes are empty -- nothing to restock."));
            return;
        }

        boolean coinPurseLoaded = YconomicsShopBridge.isAvailable();
        Inventory inventory = player.getInventory();
        int stacksMoved = 0;
        int itemsMoved = 0;
        for (int slot = MAIN_INVENTORY_START; slot < MAIN_INVENTORY_END; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (coinPurseLoaded && YconomicsShopBridge.isCoinPurse(stack)) {
                continue;
            }
            if (!stock.containsKey(BuiltInRegistries.ITEM.getKey(stack.getItem()))) {
                continue;
            }
            int before = stack.getCount();
            ContainerDeposit.depositIntoAny(boxes, stack);
            int moved = before - stack.getCount();
            if (moved > 0) {
                stacksMoved++;
                itemsMoved += moved;
            }
        }

        if (itemsMoved == 0) {
            player.sendSystemMessage(Component.literal("Nothing in your inventory matches what this plot's boxes already stock."));
            return;
        }
        player.inventoryMenu.broadcastChanges();
        player.sendSystemMessage(Component.literal(
                "Auto-Dropped " + itemsMoved + " item(s) across " + stacksMoved + " stack(s) into this plot's boxes."));
    }
}
