package com.github.cerealklla.settlemynts.guardhouse;

import java.util.List;

import com.github.cerealklla.settlemynts.api.Settlemynts;
import com.github.cerealklla.settlemynts.bridge.BlueprintsConstructionBridge;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.zone.ContainerDeposit;
import com.github.cerealklla.settlemynts.zone.ContainerWithdraw;
import com.github.cerealklla.settlemynts.zone.MidnightWorkQueue;
import com.github.cerealklla.settlemynts.zone.PlotRecord;
import com.github.cerealklla.settlemynts.zone.ShopResource;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModList;

/**
 * "Town Hall Core should have a menu option for 'Defense Spending'... specify how much money to top
 * up into each Guardhouse in the settlement independently. Then at midnight the money will transfer
 * and the guardhouse will then have funds available to purchase gear and food" (explicit user spec,
 * 2026-10-16). Driven from {@code zone.ShopMidnightRestockTicker}, same day-boundary as every other
 * midnight pass -- deliberately runs BEFORE {@code zone.PlannedInventoryClearing#settleGroup} and
 * {@link GuardhouseFoodAutoBuy#buyForSettlement}, so gold transferred this same tick is already on
 * hand for both of those to spend the very same night, not just starting the following day.
 *
 * <p>Gold is drawn from the settlement's Town Hall plot's own boxes (the same "treasury" every other
 * Town-Hall-spends-gold flow in this mod already draws from, e.g. "Establish Highway"'s 1000-nugget
 * cost) -- capped by however much is actually there, same best-effort shape as every other
 * gold/resource transfer in this mod. Deposited into the Guardhouse plot's own regular boxes (not
 * its dedicated food {@link Container}, which only ever holds food) via {@link
 * Settlemynts#resolvePlotBoxes} -- the exact same place {@link GuardhouseFoodAutoBuy} and the
 * Planned-Inventory-driven gear purchases (see {@code guardhouse.GuardKitStandPreferredInventory})
 * both already draw their own payment gold from.
 */
public final class DefenseSpendingTransfer {

    private static final ShopResource GOLD_NUGGETS = ShopResource.ofItem(BuiltInRegistries.ITEM.getKey(Items.GOLD_NUGGET));

    private DefenseSpendingTransfer() {
    }

    /**
     * Enqueues one job per Guardhouse instead of transferring to all of them synchronously in one
     * call -- proactive defense-in-depth, 2026-10-21, alongside the rest of the midnight pipeline's
     * chunking (see {@code zone.PlannedInventoryClearing}'s decisions.md entries for the three real
     * incidents that motivated auditing every step of this pipeline). This method's own per-guardhouse
     * work is small and bounded on its own (no nested per-listing loop like {@link
     * GuardhouseFoodAutoBuy}), so no further splitting below "one job per guardhouse" is needed.
     */
    public static void enqueueForSettlement(ServerLevel level, GhostTownHallCoreEntity core, MidnightWorkQueue queue) {
        PlotRecord townHall = core.townHallPlot().orElse(null);
        if (townHall == null) {
            return;
        }
        List<Container> townHallBoxes = Settlemynts.resolvePlotBoxes(level, townHall.plotId());
        if (townHallBoxes.isEmpty()) {
            return;
        }
        for (PlotRecord plot : core.getPlots()) {
            if (!plot.zoneTypeId().equals(GuardhouseConstants.GUARDHOUSE_ZONE_TYPE_ID)) {
                continue;
            }
            queue.enqueue(() -> GuardhouseIndex.get(level.getServer()).get(plot.plotId()).ifPresent(pos -> {
                ServerLevel guardhouseLevel = level.getServer().getLevel(pos.dimension());
                if (guardhouseLevel != null && guardhouseLevel.getBlockEntity(pos.pos()) instanceof GuardhouseBlockEntity guardhouse) {
                    transferToGuardhouse(level, townHall, townHallBoxes, plot, guardhouse);
                }
            }));
        }
    }

    private static void transferToGuardhouse(ServerLevel level, PlotRecord townHall, List<Container> townHallBoxes,
                                              PlotRecord guardhousePlot, GuardhouseBlockEntity guardhouse) {
        int amount = guardhouse.defenseSpendingAllocation();
        if (amount <= 0) {
            return;
        }
        List<ItemStack> withdrawn = ContainerWithdraw.drain(townHallBoxes, GOLD_NUGGETS, amount);
        int actuallyMoved = withdrawn.stream().mapToInt(ItemStack::getCount).sum();
        if (actuallyMoved <= 0) {
            return;
        }
        List<Container> guardhouseBoxes = Settlemynts.resolvePlotBoxes(level, guardhousePlot.plotId());
        for (ItemStack stack : withdrawn) {
            ContainerDeposit.depositIntoAny(guardhouseBoxes, stack);
        }
        if (ModList.get().isLoaded("blueprynts")) {
            BlueprintsConstructionBridge.appendLedgerEntry(level, townHall,
                    "Defense Spending -" + actuallyMoved + " to " + guardhousePlot.name());
            BlueprintsConstructionBridge.appendLedgerEntry(level, guardhousePlot,
                    "Defense Spending +" + actuallyMoved + " from Town Hall");
        }
    }
}
