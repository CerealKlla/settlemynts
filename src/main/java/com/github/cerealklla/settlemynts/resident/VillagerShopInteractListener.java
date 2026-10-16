package com.github.cerealklla.settlemynts.resident;

import com.github.cerealklla.settlemynts.SettlemyntsMod;
import com.github.cerealklla.settlemynts.plotsign.ShopAnchor;
import com.github.cerealklla.settlemynts.zone.NaturalVillagePlotGenerator;
import com.github.cerealklla.settlemynts.zone.PlotNpc;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.villager.Villager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Right-clicking (or the "press G" hotkey, via {@code plotsign.PlotShopProximityTicker}/{@code
 * SettlemyntsModClient}) a natural village's own trading Villager, or a player-settlement plot NPC
 * ({@code resident.ResidentVillagerEntity}, {@code farm.FarmerWorkerEntity}, {@code
 * lumberyard.LumberjackWorkerEntity}, {@code mineshaft.MinerWorkerEntity} -- see the shared
 * {@link PlotNpc} marker), opens the unified NPC Menu -- explicit user spec, 2026-10-16: "G or right
 * click now opens a GUI with menu options." Reworked from this listener's earlier direct
 * {@code handleRequestShop}/{@code handleRequestShopWishlist} dispatch (store-only, or wishlist-only)
 * into the same {@link SettlemyntsMod#openNpcMenu} both this and the "G" key now share, so a
 * right-click and "G" are just two different triggers into one identical menu. Vanilla's own trade
 * screen is still canceled so the two don't fight over the same click.
 */
public final class VillagerShopInteractListener {

    @SubscribeEvent
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        if (event.getTarget() instanceof PlotNpc npc && npc.plotId() != null) {
            ShopAnchor.Npc anchor = new ShopAnchor.Npc(event.getTarget().getId());
            if (SettlemyntsMod.npcMenuHasAnyOption(player, level, anchor)) {
                event.setCanceled(true);
                SettlemyntsMod.openNpcMenu(player, level, anchor);
            }
            return;
        }
        if (!(event.getTarget() instanceof Villager villager) || villager.getClass() != Villager.class) {
            return;
        }
        if (!villager.getPersistentData().contains(NaturalVillagePlotGenerator.PLOT_ID_TAG)) {
            return;
        }
        ShopAnchor.Npc anchor = new ShopAnchor.Npc(villager.getId());
        if (!SettlemyntsMod.npcMenuHasAnyOption(player, level, anchor)) {
            return;
        }
        event.setCanceled(true);
        SettlemyntsMod.openNpcMenu(player, level, anchor);
    }
}
