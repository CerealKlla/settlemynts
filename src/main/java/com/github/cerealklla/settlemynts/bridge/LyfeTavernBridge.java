package com.github.cerealklla.settlemynts.bridge;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.fml.ModList;

/**
 * Lyfe's new Tavern Companion Token mechanism (its own design-document.md, {@code companion}
 * package) -- design doc Section 17's "companion summoning token, same concept to how Allays work."
 * Same isolation convention as every other bridge in this package: no other class may reference
 * {@code com.github.cerealklla.lyfe.api.Lyfe} directly.
 */
public final class LyfeTavernBridge {

    private LyfeTavernBridge() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded("lyfe");
    }

    /**
     * Mints a Companion Token bound to {@code hiredNpc}'s current live state and gives it to {@code
     * player} -- see {@code api.Lyfe#mintCompanionToken}'s own doc. Does not discard {@code
     * hiredNpc}; {@link com.github.cerealklla.settlemynts.tavern.TavernHireHandler} does that itself
     * right after this call, same as every other "pick this entity up into an item" flow in this mod.
     */
    public static void mintCompanionToken(ServerPlayer player, LivingEntity hiredNpc, String displayName) {
        com.github.cerealklla.lyfe.api.Lyfe.mintCompanionToken(player, hiredNpc, displayName);
    }
}
