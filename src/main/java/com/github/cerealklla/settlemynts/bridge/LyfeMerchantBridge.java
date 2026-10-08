package com.github.cerealklla.settlemynts.bridge;

import com.github.cerealklla.lyfe.api.Lyfe;
import com.github.cerealklla.lyfe.skill.Skills;

import net.minecraft.world.entity.player.Player;
import net.neoforged.fml.ModList;

/**
 * Grants Merchant skill XP to the buyer of a Settlement Shop purchase ({@code
 * SettlemyntsMod#buyFromShop}) -- the player-shop equivalent of Lyfe's own {@code
 * merchant.MerchantListener#onTrade}, which only ever fires for NPC villager trades. Optional soft
 * dependency, same isolation convention as the other bridges in this package -- every call site
 * elsewhere in Settlemynts must check {@link #isLoaded()} itself before touching this class at all,
 * so its {@code Lyfe.*}-referencing bytecode is never loaded on a Lyfe-less server.
 */
public final class LyfeMerchantBridge {

    private LyfeMerchantBridge() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded("lyfe");
    }

    /** 1:1 XP per nugget charged, matching {@code MerchantListener#onTrade}'s own baseline-value convention. */
    public static void grantMerchantXp(Player player, long nuggetsCharged) {
        if (nuggetsCharged > 0) {
            Lyfe.addXp(player, Skills.MERCHANT_ID, nuggetsCharged);
        }
    }
}
