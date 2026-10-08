package com.github.cerealklla.settlemynts.bridge;

import com.github.cerealklla.lyfe.api.Lyfe;
import com.github.cerealklla.lyfe.merchant.MerchantListener;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;

/**
 * Grants Merchant skill XP (and, 2026-10-08, auto-raises Coin Purse tier the same way a real NPC
 * trade would) to the buyer of a Settlement Shop purchase ({@code SettlemyntsMod#buyFromShop}) --
 * the player-shop equivalent of Lyfe's own {@code merchant.MerchantListener#onTrade}, which only ever
 * fires for NPC villager trades. Optional soft dependency, same isolation convention as the other
 * bridges in this package -- every call site elsewhere in Settlemynts must check {@link #isLoaded()}
 * itself before touching this class at all, so its {@code Lyfe.*}-referencing bytecode is never
 * loaded on a Lyfe-less server.
 *
 * <p><b>Real bug fixed 2026-10-08</b>: this originally only called {@code Lyfe.addXp} directly, so a
 * player leveling Merchant purely through shop purchases got XP/levels but their Coin Purse tier
 * stayed stuck at 0 forever (the auto-raise formula lived only inside {@code
 * MerchantListener#onTrade}, never invoked for this path). Fixed by delegating to {@code
 * MerchantListener}'s own extracted {@code grantXpAndRaiseCoinPurseTier}, the exact same code path
 * an NPC trade uses, instead of duplicating (and under-implementing) it here.
 */
public final class LyfeMerchantBridge {

    private LyfeMerchantBridge() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded("lyfe");
    }

    /** 1:1 XP per nugget charged, matching {@code MerchantListener#onTrade}'s own baseline-value convention. */
    public static void grantMerchantXp(ServerPlayer player, long nuggetsCharged) {
        if (nuggetsCharged > 0) {
            MerchantListener.grantXpAndRaiseCoinPurseTier(player, (int) nuggetsCharged);
        }
    }

    /**
     * How much a Settlement Shop purchase should discount the buyer, as a fraction (2026-10-08) --
     * the exact same per-player Merchant-skill formula {@code MerchantListener} already applies to
     * vanilla NPC trades, now reused here so both trading systems treat a skilled Merchant
     * identically. Deliberately separate from {@code shop.ShopPricing}'s own shared sell/buy formula
     * -- this is a per-player runtime modifier applied on top, never baked into the stored listing
     * price (explicit user requirement).
     */
    public static double getPriceBonusFraction(ServerPlayer player) {
        return Lyfe.getMerchantPriceBonusFraction(player);
    }
}
