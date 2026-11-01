package com.github.cerealklla.settlemynts.tavern;

import com.github.cerealklla.settlemynts.bridge.MyssionsQuestBridge;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Right-clicking a seated {@link TavernNpcEntity} -- same event-based interception shape as {@code
 * resident.VillagerShopInteractListener} (vanilla's own trade screen is canceled so the two don't
 * fight over the same click; a profession-less NPC has no real trades anyway, but this is explicit
 * rather than relying on that).
 *
 * <p><b>Opens a "Talk" prompt instead of instantly attempting payment, 2026-10-31 correction</b>
 * (explicit user spec: "simply right clicking the npc and instantly having a quest is shitty...
 * spell out the quest details... let the player decide") -- see {@link OpenTavernHireMenuPayload}'s
 * own doc. The real pay/Trial-start action only happens once the player clicks that screen's button,
 * via {@link TavernHireAcceptPayload} -- see {@link TavernHireHandler#attemptHire}.
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
        HireRequirement requirement = npc.hireRequirement();
        if (requirement == null) {
            return;
        }
        boolean isTrial = requirement instanceof HireRequirement.TrialRequirement;
        boolean alreadyActiveTrial = isTrial && MyssionsQuestBridge.isLoaded()
                && MyssionsQuestBridge.hasActiveTavernTrial(player, npc.tier());
        PacketDistributor.sendToPlayer(player, new OpenTavernHireMenuPayload(
                npc.getId(), npc.getName().getString(), requirement.describe().getString(), isTrial, alreadyActiveTrial));
    }
}
