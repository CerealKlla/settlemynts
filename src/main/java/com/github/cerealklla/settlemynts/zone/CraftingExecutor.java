package com.github.cerealklla.settlemynts.zone;

import java.util.List;
import java.util.Map;

import com.github.cerealklla.settlemynts.bridge.LyfeCraftingBridge;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/**
 * NPC plot crafting (2026-10-09) -- the one place that mirrors Lyfe's own {@code
 * craft.CraftingStructureMenu#attemptCraft} consumption algorithm against a plot's boxes instead of a
 * live {@code ServerPlayer}'s inventory, shared by both {@code PlotCraftingTicker} (continuous
 * production) and {@code PlannedInventoryClearing} (midnight self-craft fulfillment) so there's
 * exactly one implementation to keep in sync with Lyfe's real crafting behavior. No Quality/armor
 * rebalancing is applied -- those are player-skill-flavored bonuses with no player context here, so
 * NPC-crafted output is always the plain base item. Flagged, not a bug.
 */
public final class CraftingExecutor {

    private CraftingExecutor() {
    }

    /** Does {@code available} (a resource id -> count snapshot, e.g. from {@code api.Settlemynts#scanPlotItemStock}) cover one craft of {@code recipe}? */
    public static boolean canCraftOne(Map<Identifier, Integer> available, LyfeCraftingBridge.RecipeInfo recipe) {
        for (Map.Entry<Identifier, Integer> entry : recipe.specificComponents().entrySet()) {
            if (available.getOrDefault(entry.getKey(), 0) < entry.getValue()) {
                return false;
            }
        }
        for (Map.Entry<String, Integer> entry : recipe.genericComponents().entrySet()) {
            int sum = 0;
            for (Identifier member : LyfeCraftingBridge.componentGroupMembers(entry.getKey())) {
                sum += available.getOrDefault(member, 0);
            }
            if (sum < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Consumes one craft's worth of {@code recipe}'s ingredients from {@code boxes} and deposits one
     * output -- caller must have already confirmed {@link #canCraftOne} against this same box set's
     * current contents. Generic groups are drained by walking {@code
     * LyfeCraftingBridge#componentGroupMembers} in that list's own declaration order, greedily taking
     * what's needed from each member before moving to the next -- mirrors {@code
     * CraftingStructureMenu}'s own first-member-first algorithm exactly, for fidelity with how a
     * player's own crafting already consumes these groups.
     */
    public static void craftOne(List<Container> boxes, LyfeCraftingBridge.RecipeInfo recipe) {
        for (Map.Entry<Identifier, Integer> entry : recipe.specificComponents().entrySet()) {
            ContainerWithdraw.drain(boxes, ShopResource.ofItem(entry.getKey()), entry.getValue());
        }
        for (Map.Entry<String, Integer> entry : recipe.genericComponents().entrySet()) {
            int remaining = entry.getValue();
            for (Identifier member : LyfeCraftingBridge.componentGroupMembers(entry.getKey())) {
                if (remaining <= 0) {
                    break;
                }
                List<ItemStack> taken = ContainerWithdraw.drain(boxes, ShopResource.ofItem(member), remaining);
                remaining -= taken.stream().mapToInt(ItemStack::getCount).sum();
            }
        }
        ContainerDeposit.depositIntoAny(boxes, new ItemStack(BuiltInRegistries.ITEM.getValue(recipe.resultId()), 1));
    }
}
