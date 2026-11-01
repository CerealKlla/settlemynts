package com.github.cerealklla.settlemynts.tavern;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Right-clicking a seated {@link TavernNpcEntity} -- same event-based interception shape as {@code
 * resident.VillagerShopInteractListener} (vanilla's own trade screen is canceled so the two don't
 * fight over the same click; a profession-less NPC has no real trades anyway, but this is explicit
 * rather than relying on that).
 */
public final class TavernHireInteractListener {

    @SubscribeEvent
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        if (!(event.getTarget() instanceof TavernNpcEntity npc)) {
            return;
        }
        event.setCanceled(true);
        TavernHireHandler.attemptHire(player, npc);
    }
}
