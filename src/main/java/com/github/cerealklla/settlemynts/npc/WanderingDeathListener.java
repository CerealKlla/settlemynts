package com.github.cerealklla.settlemynts.npc;

import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * Marks a {@link WanderingTrader}'s {@link NpcRole#WANDERING} identity permanently deceased the
 * instant it dies -- moved here from Audyble's own {@code npc.WanderingDeathListener}, 2026-11-05 (see
 * {@link NpcIdentityStore}'s own class doc for the full ownership-move writeup), the real body of the
 * "deceased characters registry" concept (see {@link NpcIdentityStore#deceased()}). Unlike a plot-bound
 * worker, this identity is never reassigned to a replacement body -- a wandering NPC's death is final.
 */
public final class WanderingDeathListener {

    @SubscribeEvent
    public void onDeath(LivingDeathEvent event) {
        if (event.getEntity().level().isClientSide() || !(event.getEntity() instanceof WanderingTrader trader)) {
            return;
        }
        String deathContext = trader.getCombatTracker().getDeathMessage().getString();
        NpcIdentityStore.find(NpcIdentityKey.wandering(trader.getUUID()))
                .ifPresent(identity -> NpcIdentityStore.put(identity.withDeceased(deathContext)));
    }
}
