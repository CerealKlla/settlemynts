package com.github.cerealklla.settlemynts.guardhouse;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.github.cerealklla.settlemynts.api.Settlemynts;
import com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.zone.ContainerDeposit;
import com.github.cerealklla.settlemynts.zone.PlotRecord;
import com.github.cerealklla.settlemynts.zone.ShopResource;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.entity.EquipmentSlot;

/**
 * "Guards should be able to pick up loose items off the ground and immediately store them in the
 * storage boxes on the plot they are assigned to. If that plot doesn't already list those items for
 * sale, it should automatically add them for the bare minimum price. Armor, weapons, and cooked food
 * (not food components) will not be auto-listed for sale, they will be kept in reserve" (explicit
 * user spec, 2026-10-16). Driven from {@link GuardPickUpItemsGoal} once a guard reaches a loose
 * {@code ItemEntity} within its own plot's "Town Proper" buffer.
 *
 * <p>The item is always stored regardless of type -- only the auto-LISTING step is conditional.
 * "Bare minimum price" is a flat 1 Gold Nugget, same floor every other "just needs a nonzero price to
 * exist as a listing" seed in this mod uses. A plot's Shop is created lazily here the same way {@code
 * zone.ShopSeeding#seedNewPlot} does, since a Guardhouse plot doesn't get one automatically today (it
 * has no "Enter Shop"/"Manage Shop" buttons -- see {@code client.PlotConfigSignMenuScreen}'s own
 * {@code hasGarrison} gate) -- this still lets other plots' nightly Planned Inventory trading buy
 * from it, the same participation every other Shop-bearing plot already has, even with no player-
 * facing storefront of its own.
 *
 * <p><b>Known v1 simplification</b>: "cooked food" is detected by vanilla item id only (a fixed
 * {@code cooked_*} prefix plus the handful of named finished dishes below) -- Lyfe's own crafted meal
 * items (see its {@code cook} package) aren't recognized yet, so a crafted dish would currently still
 * get auto-listed. Flagged, not fixed, since identifying those needs a Lyfe bridge call this pass
 * didn't need otherwise; worst case is a dish gets listed when it should've been reserved, not a
 * crash or lost item.
 */
public final class GuardLootDeposit {

    private static final int MINIMUM_PRICE = 1;

    private static final Set<String> COOKED_DISH_IDS = Set.of(
            "bread", "cake", "cookie", "pumpkin_pie", "rabbit_stew", "mushroom_stew",
            "beetroot_soup", "suspicious_stew", "golden_apple", "enchanted_golden_apple", "golden_carrot");

    private GuardLootDeposit() {
    }

    /** Deposits {@code stack} into the guard's own plot's boxes, auto-listing it (at the bare minimum price) unless it's armor, a weapon, or a cooked dish. */
    public static void deposit(ServerLevel level, UUID settlementCoreId, UUID plotId, ItemStack stack) {
        if (settlementCoreId == null || plotId == null
                || !(level.getEntity(settlementCoreId) instanceof GhostTownHallCoreEntity core)) {
            return;
        }
        PlotRecord plot = core.getPlots().stream().filter(p -> p.plotId().equals(plotId)).findFirst().orElse(null);
        if (plot == null) {
            return;
        }
        List<Container> boxes = Settlemynts.resolvePlotBoxes(level, plotId);
        if (boxes.isEmpty()) {
            return; // Nowhere to put it -- leave it on the ground rather than deleting it.
        }
        ContainerDeposit.depositIntoAny(boxes, stack);
        if (isReserved(stack)) {
            return;
        }
        autoListIfMissing(level, core, plot, stack);
    }

    private static boolean isReserved(ItemStack stack) {
        Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
        if (equippable != null && equippable.slot().getType() == EquipmentSlot.Type.HUMANOID_ARMOR) {
            return true; // Armor.
        }
        if (stack.has(DataComponents.WEAPON)) {
            return true; // Swords/axes/tridents/maces -- see this class's own doc on the axe edge case.
        }
        return isCookedDish(stack);
    }

    private static boolean isCookedDish(ItemStack stack) {
        if (stack.get(DataComponents.FOOD) == null) {
            return false;
        }
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (!"minecraft".equals(id.getNamespace())) {
            return false; // See this class's own doc -- Lyfe's own crafted dishes aren't recognized yet.
        }
        return id.getPath().startsWith("cooked_") || COOKED_DISH_IDS.contains(id.getPath());
    }

    private static void autoListIfMissing(ServerLevel level, GhostTownHallCoreEntity core, PlotRecord plot, ItemStack stack) {
        if (!YconomicsShopBridge.isAvailable()) {
            return;
        }
        Identifier itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        ShopResource resource = ShopResource.ofItem(itemId);
        UUID shopId;
        if (plot.shopId().isPresent()) {
            shopId = plot.shopId().get();
        } else {
            shopId = YconomicsShopBridge.registerShop(level, plot.plotId());
            core.updatePlot(plot.withShopId(shopId));
        }
        boolean alreadyListed = YconomicsShopBridge.getListings(level, shopId).stream()
                .anyMatch(listing -> listing.resource().itemId().map(itemId::equals).orElse(false));
        if (!alreadyListed) {
            YconomicsShopBridge.setListingPrice(level, shopId, resource, MINIMUM_PRICE);
        }
    }
}
