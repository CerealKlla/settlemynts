package com.github.cerealklla.settlemynts.resident;

import com.github.cerealklla.settlemynts.SettlemyntsMod;
import com.github.cerealklla.settlemynts.plotsign.ShopAnchor;
import com.github.cerealklla.settlemynts.zone.NaturalVillagePlotGenerator;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.villager.Villager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Right-clicking (or the existing "press G" hotkey, via {@code plotsign.PlotShopProximityTicker})
 * a natural village's own trading Villager opens the same Shop screen a Plot Config Sign's "Enter
 * Shop" would -- Part C of the "Natural settlements..." plan, 2026-10-08, per explicit user
 * direction ("treat the villagers who have trades as the plot sales signs, including having the
 * hotkey to open the menu"). Villager AI itself is untouched (profession, trading offers, breeding
 * all keep working exactly as vanilla) -- only the right-click interaction is redirected; vanilla's
 * own trade screen is canceled so the two don't fight over the same click. Same event shape as
 * Lyfe's existing {@code knowledge.SignListener#onEntityInteract} (there, for an {@code ItemFrame}).
 */
public final class VillagerShopInteractListener {

    @SubscribeEvent
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        if (!(event.getTarget() instanceof Villager villager) || villager.getClass() != Villager.class) {
            return;
        }
        if (!villager.getPersistentData().contains(NaturalVillagePlotGenerator.PLOT_ID_TAG)) {
            return;
        }
        event.setCanceled(true);
        SettlemyntsMod.handleRequestShop(player, new ShopAnchor.Npc(villager.getId()), false);
    }
}
