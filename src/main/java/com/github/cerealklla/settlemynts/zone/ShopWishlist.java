package com.github.cerealklla.settlemynts.zone;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;

import com.github.cerealklla.settlemynts.api.Settlemynts;
import com.github.cerealklla.settlemynts.bridge.LyfeCraftingBridge;
import com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge;

/**
 * A shop's public "what do I still need" list (2026-10-10, explicit user request: "I'd like shops to
 * have a public wishlist, which shows what the shop needs in order to accomplish its preferred
 * inventory"). Deliberately read-only/side-effect-free -- unlike the midnight engine, this never
 * moves any items or gold itself.
 *
 * <p><b>Crafting inputs, never the shop's own finished-good stock targets directly</b> (corrected
 * 2026-10-10, real feedback: "this should be the list of things the shop would buy from other shops
 * in order to craft the things it wants to sell. This should NOT list things that the shop wants to
 * sell... I'd expect the Armorer to have Completed Armor for sale, and their wishlist would be the
 * components they could use to craft that armor if they don't have enough on-hand"). A {@link
 * PlannedInventoryTarget} alone doesn't say whether it's a finished good or a raw material -- the
 * real signal is whether the shop has a real Shop listing for it (something it sells).
 *
 * <p>A **second real correction, same day**: simply excluding a sold item's own deficit (the first
 * fix) left the wishlist empty whenever the shop had never separately been given its own raw-material
 * targets (true for any player-owned plot -- {@code ShopSeeding#autoPopulatePlannedInventory}'s
 * material-seeding only ever runs for NPC-owned shops at creation time). Fixed properly: a sold
 * item's own deficit is no longer dropped, it's **translated** into its recipe's material shortfall
 * -- via {@code bridge.LyfeCraftingBridge}, only when the plot actually has a sufficiently-tiered
 * crafting structure for that recipe ("he has a crafting table capable of building them"), the same
 * tier gate {@code zone.PlotCraftingStructures}/{@code PlannedInventoryClearing} already use.
 *
 * <p>**A third correction, 2026-10-11**: any Planned Inventory target that *wasn't* a currently-sold,
 * in-deficit, craftable finished good was still being listed directly (a leftover "direct deficit"
 * fallback) -- in practice this meant a plot's own raw-material targets (auto-seeded for NPC shops
 * specifically to support crafting, see {@code ShopSeeding#autoPopulatePlannedInventory}) showed up
 * in the public wishlist too, which is exactly what the previous fix above was supposed to prevent.
 * Explicit user correction: the wishlist is **only** "components needed to craft an item the shop
 * has listed for sale but doesn't have enough of in stock, and could craft if it had the
 * components" -- nothing else. The "direct deficit" fallback is gone entirely; a target that isn't a
 * sold, in-deficit, known, tier-craftable recipe contributes nothing to the wishlist at all, no
 * matter what it is.
 */
public final class ShopWishlist {

    private ShopWishlist() {
    }

    /** 10% above {@code normalPricePerUnit}, always at least 1 higher so a cheap item still has a real premium. */
    public static int premiumPrice(int normalPricePerUnit) {
        return normalPricePerUnit + Math.max(1, Math.round(normalPricePerUnit * 0.10F));
    }

    public record DeficitEntry(ShopResource resource, int quantityNeeded) {
    }

    /**
     * Every raw material this plot would need to buy to actually craft its way out of a real
     * shortfall on something it sells, with how many units short. A Planned Inventory target that
     * isn't a sold, in-deficit, known, tier-craftable recipe contributes nothing at all -- see class
     * doc's third correction for why this is deliberately narrower than "everything in deficit."
     */
    public static List<DeficitEntry> computeDeficits(ServerLevel level, PlotRecord plot) {
        List<DeficitEntry> deficits = new ArrayList<>();
        if (plot.plannedInventory().isEmpty() || !LyfeCraftingBridge.isLoaded()) {
            return deficits;
        }
        List<Container> boxes = Settlemynts.resolvePlotBoxes(level, plot.plotId());
        Set<Identifier> soldItemIds = plot.shopId()
                .map(shopId -> YconomicsShopBridge.getListings(level, shopId).stream()
                        .map(view -> view.resource().itemId())
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .collect(Collectors.toSet()))
                .orElse(Set.of());

        Map<Identifier, Integer> materialUnitsRequired = new LinkedHashMap<>();
        int structureTier = -1; // Lazily resolved -- -1 means "not checked yet."

        for (PlannedInventoryTarget target : plot.plannedInventory()) {
            if (target.isTag() || !soldItemIds.contains(target.resourceKey())) {
                continue; // Only a sold, concrete finished good can translate into a wishlist entry at all.
            }
            ShopResource resource = ShopResource.ofItem(target.resourceKey());
            int currentStock = ContainerWithdraw.countAvailable(boxes, resource);
            int unitsShort = target.targetCount() - currentStock;
            if (unitsShort <= 0) {
                continue;
            }
            Optional<LyfeCraftingBridge.RecipeInfo> recipeOpt = LyfeCraftingBridge.getRecipe(target.resourceKey());
            if (recipeOpt.isEmpty()) {
                continue; // Sold, in deficit, but not a known recipe -- nothing to wishlist for it.
            }
            if (structureTier < 0) {
                structureTier = PlotCraftingStructures.maxCraftingStructureTier(level, plot.plotId());
            }
            LyfeCraftingBridge.RecipeInfo recipe = recipeOpt.get();
            if (structureTier < recipe.tier()) {
                continue; // Can't actually craft it here regardless -- same gate PlannedInventoryClearing itself uses.
            }
            for (Map.Entry<Identifier, Integer> component : recipe.specificComponents().entrySet()) {
                materialUnitsRequired.merge(component.getKey(), component.getValue() * unitsShort, Integer::sum);
            }
            for (Map.Entry<String, Integer> group : recipe.genericComponents().entrySet()) {
                List<Identifier> members = LyfeCraftingBridge.componentGroupMembers(group.getKey());
                if (members.isEmpty()) {
                    continue;
                }
                // Simple starting default, same simplification ShopSeeding#autoPopulatePlannedInventory
                // already uses for this exact situation -- the midnight engine's own cheapest-member
                // logic is what actually matters at real trade time, this is just what to ask a player for.
                materialUnitsRequired.merge(members.get(0), group.getValue() * unitsShort, Integer::sum);
            }
        }

        for (Map.Entry<Identifier, Integer> entry : materialUnitsRequired.entrySet()) {
            ShopResource resource = ShopResource.ofItem(entry.getKey());
            int shortfall = entry.getValue() - ContainerWithdraw.countAvailable(boxes, resource);
            if (shortfall > 0) {
                deficits.add(new DeficitEntry(resource, shortfall));
            }
        }
        return deficits;
    }

    /** Whether {@code plot} currently has any deficit at all -- cheap short-circuit for proximity checks that don't need the full list. */
    public static boolean hasAnyDeficit(ServerLevel level, PlotRecord plot) {
        if (plot.plannedInventory().isEmpty()) {
            return false;
        }
        return !computeDeficits(level, plot).isEmpty();
    }

    /** The "normal" per-unit price a deficit resource is worth -- the Zone Type catalog's own suggested price (see {@code ShopSeeding#suggestedPriceFor}'s own doc for why this, not a real Shop listing, is the right baseline: a shop buying raw materials to craft with usually has no sell listing for them at all). Never zero -- falls back to 1 so a premium can always be computed. */
    public static int normalPriceFor(ServerLevel level, PlotRecord plot, BlockPos plotAnchor, ShopResource resource) {
        return Math.max(1, ShopSeeding.suggestedPriceFor(level, plot, plotAnchor, resource));
    }
}
