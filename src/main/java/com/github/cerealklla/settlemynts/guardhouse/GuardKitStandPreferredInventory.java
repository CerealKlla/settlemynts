package com.github.cerealklla.settlemynts.guardhouse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.github.cerealklla.kyt.loadout.LoadoutRecord;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.zone.PlannedInventoryTarget;
import com.github.cerealklla.settlemynts.zone.PlotRecord;

import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/**
 * "Keep Spares On Hand" (explicit user spec, 2026-10-16): "The Guard Kit Stand should have a 'Keep
 * Spares On Hand' # for the plot's 'preferred inventory'. These will reside within boxes inside the
 * plot like all other plots, with the exception that food can only be stored in the Food Stores.
 * Each stand has its own list # of preferred kits to keep on hand." Confirmed with the user: counts
 * sum across every stand sharing an item (two stands each wanting 2 spares of the same sword means 4
 * total kept stocked); recomputed wholesale any time any stand in the plot changes.
 *
 * <p>Writes real {@link PlannedInventoryTarget} entries (flagged {@code derivedFromGuardKitStand})
 * into the plot's existing {@code plannedInventory()} list -- {@code zone.PlannedInventoryClearing}'s
 * existing nightly inter-plot trading then funds them with zero changes needed there. Food items are
 * excluded entirely (per the explicit spec) -- they aren't tracked by this mechanism at all, since
 * they can only ever live in a Guardhouse's own Food Store, a separate container {@code
 * api.Settlemynts#resolvePlotBoxes} doesn't reach (see {@code GuardhouseFoodAutoBuy}'s own doc for
 * that separate, Food-Store-specific threshold/funding mechanism instead).
 */
public final class GuardKitStandPreferredInventory {

    private GuardKitStandPreferredInventory() {
    }

    /** Convenience overload resolving core/plot from raw ids -- the shape every call site here actually has in hand. */
    public static void recalculate(ServerLevel level, UUID settlementCoreId, UUID plotId) {
        if (settlementCoreId == null || plotId == null || !(level.getEntity(settlementCoreId) instanceof GhostTownHallCoreEntity core)) {
            return;
        }
        recalculate(level, core, plotId);
    }

    public static void recalculate(ServerLevel level, GhostTownHallCoreEntity core, UUID plotId) {
        PlotRecord plot = core.getPlots().stream().filter(p -> p.plotId().equals(plotId)).findFirst().orElse(null);
        if (plot == null) {
            return;
        }

        Map<Identifier, Integer> summedSpares = new LinkedHashMap<>();
        for (Map.Entry<UUID, GlobalPos> entry : GuardKitStandIndex.get(level.getServer()).all().entrySet()) {
            GlobalPos pos = entry.getValue();
            ServerLevel standLevel = level.getServer().getLevel(pos.dimension());
            if (standLevel == null || !(standLevel.getEntity(entry.getKey()) instanceof GuardKitStandEntity stand)
                    || !plotId.equals(stand.plotId()) || stand.sparesOnHand() <= 0) {
                continue;
            }
            Optional<LoadoutRecord> record = stand.boundKytName().flatMap(com.github.cerealklla.settlemynts.bridge.KytGuardhouseBridge::getKyt);
            if (record.isEmpty()) {
                continue;
            }
            for (ItemStack equipped : equippedItems(record.get())) {
                if (equipped.isEmpty() || equipped.get(DataComponents.FOOD) != null) {
                    continue; // "food can only be stored in the Food Stores" -- excluded entirely here.
                }
                Identifier itemId = BuiltInRegistries.ITEM.getKey(equipped.getItem());
                summedSpares.merge(itemId, stand.sparesOnHand(), Integer::sum);
            }
        }

        List<PlannedInventoryTarget> updated = new ArrayList<>();
        for (PlannedInventoryTarget existing : plot.plannedInventory()) {
            if (!existing.derivedFromGuardKitStand()) {
                updated.add(existing); // Never touch a player's own manually-set target.
            }
        }
        summedSpares.forEach((itemId, count) -> updated.add(new PlannedInventoryTarget(itemId, false, count, true)));
        core.updatePlot(plot.withPlannedInventory(updated));
    }

    /** The same 6 potential equip slots {@code GuardEntity#equipFromLoadoutUsingBoxes} draws from -- mirrors that selection exactly so "spares" always matches what a spawn actually consumes. */
    private static List<ItemStack> equippedItems(LoadoutRecord record) {
        List<ItemStack> items = new ArrayList<>(List.of(record.helmet(), record.chestplate(), record.leggings(), record.boots(), record.offhand()));
        for (ItemStack stack : record.hotbar()) {
            if (!stack.isEmpty()) {
                items.add(stack);
                break;
            }
        }
        return items;
    }
}
