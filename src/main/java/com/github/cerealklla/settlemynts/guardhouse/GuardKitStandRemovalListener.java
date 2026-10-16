package com.github.cerealklla.settlemynts.guardhouse;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * "Leaving the plot/dying/teleporting away/logging out with one of these items in the player's
 * inventory will remove it" (explicit user spec, Guard Kit Stand overhaul, 2026-10-16) -- instant
 * handling for the death/logout cases, mirroring {@code founding.LocatorCancelListener}'s exact
 * shape. "Teleporting away" and "leaving the plot" are both already covered by {@code
 * SettlemyntsMod}'s own once-a-second tick check (position-based, so a teleport is caught the same
 * way a plain walk-away is) -- this class only adds the two cases that check alone can't reach
 * (a dead/logged-out player stops ticking).
 */
public final class GuardKitStandRemovalListener {

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && player.level() instanceof ServerLevel level) {
            SettlemyntsMod.removeStaleGuardKitStandsOutsidePlot(player, level);
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && player.level() instanceof ServerLevel level) {
            SettlemyntsMod.removeStaleGuardKitStandsOutsidePlot(player, level);
        }
    }
}
