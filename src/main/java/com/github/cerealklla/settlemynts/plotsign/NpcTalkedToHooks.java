package com.github.cerealklla.settlemynts.plotsign;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

/**
 * Fires whenever a player's NPC Menu actually opens for a real NPC -- added 2026-11-09 after a real
 * live report: "pressing 'G' doesn't count as 'talking to a villager'" for Arryval's tutorial quest.
 * Root cause: {@code SettlemyntsMod#openNpcMenu} is the one true shared choke point both the "G" key
 * ({@code requestNpcMenu}) and a plain right-click ({@code resident.VillagerShopInteractListener})
 * funnel through, but Arryval's own {@code starttown.VillagerTalkTracker} only ever listened to
 * vanilla's {@code PlayerInteractEvent.EntityInteract} -- which the right-click path still fires
 * (canceled, but still delivered), while the "G" key path sends a custom network payload straight to
 * the server and never touches that event at all. Same "fix the shared mechanism, not just the one
 * trigger someone happened to test" lesson as the chat-spam fix earlier the same day. Registered via
 * {@code api.Settlemynts#registerNpcTalkedToHook}; fired from {@code openNpcMenu} itself, so both
 * trigger paths are covered identically and can never drift apart again.
 */
public final class NpcTalkedToHooks {

    private static final List<BiConsumer<ServerPlayer, LivingEntity>> HOOKS = new CopyOnWriteArrayList<>();

    private NpcTalkedToHooks() {
    }

    public static void register(BiConsumer<ServerPlayer, LivingEntity> hook) {
        HOOKS.add(hook);
    }

    public static void fire(ServerPlayer player, LivingEntity npc) {
        for (BiConsumer<ServerPlayer, LivingEntity> hook : HOOKS) {
            hook.accept(player, npc);
        }
    }
}
