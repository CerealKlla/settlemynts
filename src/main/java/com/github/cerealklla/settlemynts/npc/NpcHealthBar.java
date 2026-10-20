package com.github.cerealklla.settlemynts.npc;

import com.github.cerealklla.settlemynts.bridge.LyfeManaBridge;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;

/**
 * A floating health-bar nameplate for this suite's custom NPCs (Guard, Resident, Farmer, Lumberjack,
 * Miner) -- explicit user request, 2026-10-19: "visible when HP is less than 100%." Reuses vanilla's
 * own nametag rendering entirely, same technique as {@code plotsign.PlotShopProximityTicker}'s "?"
 * wishlist marker -- no new client rendering code needed. Called once per {@code customServerAiStep}
 * from each NPC type; hidden again the instant health is back at max (and Mana, see below, is also
 * absent or full), so a healthy/topped-up NPC never shows a bar at all.
 *
 * <p>Appends a Mana indicator (design doc, 2026-10-20, Lyfe's own feature: "represented... above
 * NPC heads when not at 100%") via {@link LyfeManaBridge} (never {@code
 * com.github.cerealklla.lyfe.api.Lyfe} directly -- see that bridge's own doc) -- added here, in
 * Settlemynts (which already depends on Lyfe, one-way), rather than a second independent nameplate
 * writer in Lyfe, since two listeners both calling {@code setCustomName} on the same entity would
 * race and stomp each other's text. Placed on the *same* line as the health bar (two spaces apart),
 * not a second stacked line -- vanilla's own nametag rendering draws {@code
 * Entity#getDisplayName()} as a single line, with no built-in support for an embedded newline
 * actually breaking visually onto a second row.
 */
public final class NpcHealthBar {

    private static final int SEGMENTS = 10;
    private static final char FILLED = '█';
    private static final char EMPTY = '░';
    private static final String SEPARATOR = "  ";

    private NpcHealthBar() {
    }

    public static void update(LivingEntity npc) {
        Component manaLine = LyfeManaBridge.isLoaded()
                ? LyfeManaBridge.manaNameplateLine(npc).orElse(null)
                : null;

        float max = npc.getMaxHealth();
        float health = npc.getHealth();
        boolean healthFull = max <= 0.0F || health >= max;
        if (healthFull && manaLine == null) {
            if (npc.getCustomName() != null && looksLikeOwnBar(npc.getCustomName())) {
                npc.setCustomName(null);
                npc.setCustomNameVisible(false);
            }
            return;
        }

        Component name;
        if (healthFull) {
            name = manaLine;
        } else {
            float fraction = Math.max(0.0F, health / max);
            int filled = Math.round(fraction * SEGMENTS);
            ChatFormatting color = fraction > 0.66F ? ChatFormatting.GREEN : fraction > 0.33F ? ChatFormatting.YELLOW : ChatFormatting.RED;

            StringBuilder bar = new StringBuilder(SEGMENTS);
            for (int i = 0; i < SEGMENTS; i++) {
                bar.append(i < filled ? FILLED : EMPTY);
            }
            Component healthLine = Component.literal(bar.toString()).withStyle(color);
            name = manaLine == null ? healthLine : healthLine.copy().append(SEPARATOR).append(manaLine);
        }

        if (!name.equals(npc.getCustomName())) {
            npc.setCustomName(name);
            npc.setCustomNameVisible(true);
        }
    }

    private static boolean looksLikeOwnBar(Component existing) {
        String text = existing.getString();
        if (text.length() == SEGMENTS) {
            return isAllBarChars(text);
        }
        if (text.length() == SEGMENTS * 2 + SEPARATOR.length()
                && text.startsWith(SEPARATOR, SEGMENTS)) {
            return isAllBarChars(text.substring(0, SEGMENTS)) && isAllBarChars(text.substring(SEGMENTS + SEPARATOR.length()));
        }
        return false;
    }

    private static boolean isAllBarChars(String segment) {
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c != FILLED && c != EMPTY) {
                return false;
            }
        }
        return true;
    }
}
