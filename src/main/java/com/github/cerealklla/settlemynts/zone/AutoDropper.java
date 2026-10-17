package com.github.cerealklla.settlemynts.zone;

import java.util.ArrayList;
import java.util.HashMap;
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
 * Inventory} slots 9-35 only) that the plot's boxes already stock into those boxes. The Coin Purse
 * is excluded outright (checked via {@link YconomicsShopBridge}, only when Yconomics is actually
 * loaded -- no Coin Purse item exists otherwise).
 *
 * <p>Real live-test bug, 2026-10-17: a first version only checked whether an item id appeared
 * *anywhere* on the plot ({@link Settlemynts#scanItemStock}'s own plot-wide aggregate), then handed
 * every matching box to {@link ContainerDeposit#depositIntoAny} -- which fills the first box with
 * room, not specifically a box that already held that item. That let an item land in a box that
 * had never stocked it at all, as long as *some other* box on the plot happened to. Fixed by
 * resolving, per item id, the actual subset of boxes that currently contain at least one of that
 * item ({@link #boxesContaining}) and depositing into only that subset.
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
        Map<Identifier, List<Container>> matchingBoxesByItem = new HashMap<>();
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
            Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (!stock.containsKey(id)) {
                continue;
            }
            List<Container> matchingBoxes = matchingBoxesByItem.computeIfAbsent(id, key -> boxesContaining(boxes, key));
            if (matchingBoxes.isEmpty()) {
                continue;
            }
            int before = stack.getCount();
            ContainerDeposit.depositIntoAny(matchingBoxes, stack);
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

    /** Only the boxes that currently hold at least one of {@code itemId} -- see this class's own doc for why. */
    private static List<Container> boxesContaining(List<Container> boxes, Identifier itemId) {
        List<Container> matching = new ArrayList<>();
        for (Container box : boxes) {
            for (int slot = 0; slot < box.getContainerSize(); slot++) {
                ItemStack existing = box.getItem(slot);
                if (!existing.isEmpty() && BuiltInRegistries.ITEM.getKey(existing.getItem()).equals(itemId)) {
                    matching.add(box);
                    break;
                }
            }
        }
        return matching;
    }
}
