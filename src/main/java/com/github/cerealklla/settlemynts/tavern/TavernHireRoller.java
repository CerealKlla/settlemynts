package com.github.cerealklla.settlemynts.tavern;

import java.util.ArrayList;
import java.util.List;

import com.github.cerealklla.settlemynts.bridge.MyssionsQuestBridge;
import com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge;

import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;

/**
 * Rolls a seated Tavern NPC's single {@link HireRequirement} at spawn time -- "One random
 * requirement per NPC," cost scaling with Tavern tier (both explicit user choices). Flagged
 * placeholder/tunable amounts, same footing as every other numeric curve in this mod. Gold is
 * excluded from the roll on a Yconomics-less server; Trial is excluded on a Myssions-less server
 * (2026-10-31 -- the Trial is now a real Myssions quest, see {@code TavernHireHandler}'s own doc).
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

    private enum Kind { ITEM, GOLD, TRIAL }

    static HireRequirement rollRequirement(int tier, RandomSource random) {
        int clamped = Math.max(1, Math.min(5, tier));
        List<Kind> kinds = new ArrayList<>();
        kinds.add(Kind.ITEM);
        if (YconomicsShopBridge.isAvailable()) {
            kinds.add(Kind.GOLD);
        }
        if (MyssionsQuestBridge.isLoaded()) {
            kinds.add(Kind.TRIAL);
        }
        return switch (kinds.get(random.nextInt(kinds.size()))) {
            case GOLD -> new HireRequirement.GoldRequirement(20 * clamped);
            case TRIAL -> new HireRequirement.TrialRequirement(2 + clamped, 3600);
            case ITEM -> new HireRequirement.ItemRequirement(ITEM_POOL[random.nextInt(ITEM_POOL.length)], 3 + 2 * clamped);
        };
    }
}
