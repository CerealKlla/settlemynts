package com.github.cerealklla.settlemynts.plotsign;

import com.github.cerealklla.settlemynts.bridge.LyfeMerchantBridge;
import com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge;

import net.minecraft.world.item.ItemStack;

/**
 * Items a Settlement Shop must never accept into its Sell lane, regardless of whether the shop
 * would otherwise list that resource -- real request, 2026-10-20: "need to prevent player selling
 * their Recallcinite totem or coin purse." Both are bound, functionally load-bearing items (a
 * player's own recall point; their entire gold balance/Merchant-tier container) rather than
 * ordinary tradeable goods, so losing one to an accidental or spam-click sale would be a real loss,
 * not just a bad trade. Enforced at the {@code ShopCartMenu.SellSlot} level (so it can never even be
 * placed into Sell in the first place) and again in {@code SettlemyntsMod#processSellLine} as a
 * defense-in-depth filter, since that method is also reachable via the older {@code SellToShopPayload}
 * path.
 */
public final class UnsellableItems {

    private UnsellableItems() {
    }

    public static boolean isUnsellable(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (YconomicsShopBridge.isCoinPurse(stack)) {
            return true;
        }
        return LyfeMerchantBridge.isLoaded() && LyfeMerchantBridge.isRecallciniteTotem(stack);
    }
}
