package com.github.cerealklla.settlemynts.bridge;

import java.util.UUID;

import com.github.cerealklla.terrytories.api.Terrytories;
import com.github.cerealklla.terrytories.reputation.Standing;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;

/**
 * "Passive bonuses when in those territories, like a % better buy/sell prices" -- explicit user
 * spec, Friendly reputation only. Optional soft dependency, same isolation convention as the other
 * bridges in this package ({@link LyfeMerchantBridge} is the closest sibling -- every call site
 * must check {@link #isLoaded()} before touching this class at all). Deliberately a separate,
 * additive bonus fraction from {@link LyfeMerchantBridge#getPriceBonusFraction} -- a skilled
 * Merchant AND a Friendly-standing player both get their own bonus, summed at the call site before
 * being handed to {@code shop.ShopPricing#effectiveBuyCost}/{@code #effectiveSellPayout} (never
 * applied independently -- see that class's own doc on the zero-cost exploit that guards against).
 */
public final class TerrytoriesReputationBridge {

    /** Placeholder/tunable, mirrors Terrytories' own `reputation.Standing` doc. */
    public static final double FRIENDLY_PRICE_BONUS_FRACTION = 0.10;

    private TerrytoriesReputationBridge() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded("terrytories");
    }

    /**
     * {@code plotId} is resolved to its owning settlement via this mod's own {@code
     * api.Settlemynts#findSettlementCoreIdForPlot} -- a plot with no resolvable settlement (shouldn't
     * happen in practice, every Shop plot belongs to one) simply gets no bonus.
     */
    public static double getPriceBonusFraction(ServerPlayer player, ServerLevel level, UUID plotId) {
        return com.github.cerealklla.settlemynts.api.Settlemynts.findSettlementCoreIdForPlot(level, plotId)
                .map(settlementCoreId -> Terrytories.getStanding(player, settlementCoreId) == Standing.FRIENDLY
                        ? FRIENDLY_PRICE_BONUS_FRACTION : 0.0)
                .orElse(0.0);
    }
}
