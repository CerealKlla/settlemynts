package com.github.cerealklla.settlemynts.npc;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;

/**
 * A floating health-bar nameplate for this suite's custom NPCs (Guard, Resident, Farmer, Lumberjack,
 * Miner) -- explicit user request, 2026-10-19: "visible when HP is less than 100%." Reuses vanilla's
 * own nametag rendering entirely, same technique as {@code plotsign.PlotShopProximityTicker}'s "?"
 * wishlist marker -- no new client rendering code needed. Called once per {@code customServerAiStep}
 * from each NPC type; hidden again the instant health is back at max, so a healthy NPC never shows a
 * bar at all, only ever a damaged one.
 */
public final class NpcHealthBar {

    private static final int SEGMENTS = 10;
    private static final char FILLED = '█';
    private static final char EMPTY = '░';

    private NpcHealthBar() {
    }

    public static void update(LivingEntity npc) {
        float max = npc.getMaxHealth();
        float health = npc.getHealth();
        if (max <= 0.0F || health >= max) {
            if (npc.getCustomName() != null && looksLikeOwnBar(npc.getCustomName().getString())) {
                npc.setCustomName(null);
                npc.setCustomNameVisible(false);
            }
            return;
        }

        float fraction = Math.max(0.0F, health / max);
        int filled = Math.round(fraction * SEGMENTS);
        ChatFormatting color = fraction > 0.66F ? ChatFormatting.GREEN : fraction > 0.33F ? ChatFormatting.YELLOW : ChatFormatting.RED;

        StringBuilder bar = new StringBuilder(SEGMENTS);
        for (int i = 0; i < SEGMENTS; i++) {
            bar.append(i < filled ? FILLED : EMPTY);
        }
        Component name = Component.literal(bar.toString()).withStyle(color);
        if (!name.equals(npc.getCustomName())) {
            npc.setCustomName(name);
            npc.setCustomNameVisible(true);
        }
    }

    private static boolean looksLikeOwnBar(String text) {
        if (text.length() != SEGMENTS) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != FILLED && c != EMPTY) {
                return false;
            }
        }
        return true;
    }
}
