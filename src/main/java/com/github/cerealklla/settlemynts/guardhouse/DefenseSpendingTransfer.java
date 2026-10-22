package com.github.cerealklla.settlemynts.guardhouse;

import java.util.List;
import java.util.Optional;

import com.github.cerealklla.settlemynts.api.Settlemynts;
import com.github.cerealklla.settlemynts.bridge.BlueprintsConstructionBridge;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.watchtower.WatchTowerBeaconBlockEntity;
import com.github.cerealklla.settlemynts.watchtower.WatchTowerConstants;
import com.github.cerealklla.settlemynts.watchtower.WatchTowerIndex;
import com.github.cerealklla.settlemynts.zone.ContainerDeposit;
import com.github.cerealklla.settlemynts.zone.ContainerWithdraw;
import com.github.cerealklla.settlemynts.zone.MidnightWorkQueue;
import com.github.cerealklla.settlemynts.zone.PlotRecord;
import com.github.cerealklla.settlemynts.zone.ShopResource;

import net.minecraft.core.GlobalPos;
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
            if (!isEligible(plot)) {
                continue;
            }
            queue.enqueue(() -> resolveRecipient(level, plot).ifPresent(recipient ->
                    transferToRecipient(level, townHall, townHallBoxes, plot, recipient)));
        }
    }

    private static void transferToRecipient(ServerLevel level, PlotRecord townHall, List<Container> townHallBoxes,
                                              PlotRecord recipientPlot, DefenseSpendingRecipient recipient) {
        int amount = recipient.defenseSpendingAllocation();
        if (amount <= 0) {
            return;
        }
        List<ItemStack> withdrawn = ContainerWithdraw.drain(townHallBoxes, GOLD_NUGGETS, amount);
        int actuallyMoved = withdrawn.stream().mapToInt(ItemStack::getCount).sum();
        if (actuallyMoved <= 0) {
            return;
        }
        List<Container> recipientBoxes = Settlemynts.resolvePlotBoxes(level, recipientPlot.plotId());
        for (ItemStack stack : withdrawn) {
            ContainerDeposit.depositIntoAny(recipientBoxes, stack);
        }
        if (ModList.get().isLoaded("blueprynts")) {
            BlueprintsConstructionBridge.appendLedgerEntry(level, townHall,
                    "Defense Spending -" + actuallyMoved + " to " + recipientPlot.name());
            BlueprintsConstructionBridge.appendLedgerEntry(level, recipientPlot,
                    "Defense Spending +" + actuallyMoved + " from Town Hall");
        }
    }

    /** Every Zone Type "Defense Spending" can currently fund -- Guardhouse and Watch Tower today, easily extended by adding one more branch here and to {@link #resolveRecipient}. */
    public static boolean isEligible(PlotRecord plot) {
        return plot.zoneTypeId().equals(GuardhouseConstants.GUARDHOUSE_ZONE_TYPE_ID)
                || plot.zoneTypeId().equals(WatchTowerConstants.WATCH_TOWER_ZONE_TYPE_ID);
    }

    /** Resolves {@code plot}'s own {@link DefenseSpendingRecipient} block entity, if its Zone Type is eligible (see {@link #isEligible}) and its chunk is actually loaded. */
    public static Optional<DefenseSpendingRecipient> resolveRecipient(ServerLevel level, PlotRecord plot) {
        if (plot.zoneTypeId().equals(GuardhouseConstants.GUARDHOUSE_ZONE_TYPE_ID)) {
            return GuardhouseIndex.get(level.getServer()).get(plot.plotId())
                    .flatMap(pos -> resolveAt(level, pos, GuardhouseBlockEntity.class));
        }
        if (plot.zoneTypeId().equals(WatchTowerConstants.WATCH_TOWER_ZONE_TYPE_ID)) {
            return WatchTowerIndex.get(level.getServer()).get(plot.plotId())
                    .flatMap(pos -> resolveAt(level, pos, WatchTowerBeaconBlockEntity.class));
        }
        return Optional.empty();
    }

    private static <T extends DefenseSpendingRecipient> Optional<T> resolveAt(ServerLevel level, GlobalPos pos, Class<T> type) {
        ServerLevel targetLevel = level.getServer().getLevel(pos.dimension());
        if (targetLevel == null) {
            return Optional.empty();
        }
        return type.isInstance(targetLevel.getBlockEntity(pos.pos())) ? Optional.of(type.cast(targetLevel.getBlockEntity(pos.pos()))) : Optional.empty();
    }
}
