package com.github.cerealklla.settlemynts.tavern;

import com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge;

import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;

/**
 * Rolls a seated Tavern NPC's single {@link HireRequirement} at spawn time -- "One random
 * requirement per NPC," cost scaling with Tavern tier (both explicit user choices). Flagged
 * placeholder/tunable amounts, same footing as every other numeric curve in this mod. Gold is
 * excluded from the roll entirely on a Yconomics-less server (nothing to pay it with).
 */
final class TavernHireRoller {

    private static final Identifier[] ITEM_POOL = {
            Identifier.withDefaultNamespace("bread"),
            Identifier.withDefaultNamespace("iron_ingot"),
            Identifier.withDefaultNamespace("gold_ingot"),
            Identifier.withDefaultNamespace("emerald"),
    };

    private TavernHireRoller() {
    }

    static HireRequirement rollRequirement(int tier, RandomSource random) {
        int clamped = Math.max(1, Math.min(5, tier));
        boolean goldAvailable = YconomicsShopBridge.isAvailable();
        int kinds = goldAvailable ? 3 : 2;
        int roll = random.nextInt(kinds);
        if (roll == 0) {
            Identifier itemId = ITEM_POOL[random.nextInt(ITEM_POOL.length)];
            return new HireRequirement.ItemRequirement(itemId, 3 + 2 * clamped);
        }
        if (roll == 1) {
            return new HireRequirement.TrialRequirement(2 + clamped, 3600);
        }
        return new HireRequirement.GoldRequirement(20 * clamped);
    }
}
