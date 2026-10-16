package com.github.cerealklla.settlemynts.plotsign.client;

import com.github.cerealklla.settlemynts.SettlemyntsModClient;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.neoforged.neoforge.client.gui.GuiLayer;

/**
 * "Press &lt;hotkey&gt; to talk" -- floating text centered over the player's XP bar while within 4
 * blocks of an eligible NPC (design doc Section 14a, 2026-10-05). Driven entirely by {@link
 * ClientShopPromptState} (updated from {@code PlotShopPromptPayload}) -- no per-frame server
 * round-trip. Same render-from-client-state shape as {@code zone.PlotValidityOverlay}, positioned
 * instead right above vanilla's own XP bar (an eyeballed offset, like every other HUD margin in this
 * suite).
 *
 * <p><b>Reworked 2026-10-16</b>, NPC Menu overhaul (explicit user spec: "Remove 'Press H to talk' /
 * Change G to 'Press G to talk'") -- collapsed back down to a single line/single key. What used to
 * be two separate destinations ("open shop" via G, "chat" via H) are now both just entries inside
 * the one menu G opens -- see {@code client.NpcMenuScreen}.
 */
public final class ShopPromptOverlay implements GuiLayer {

    private static final int MARGIN_FROM_BOTTOM = 36; // just above vanilla's XP bar
    private static final int PADDING = 3;
    private static final int TEXT_COLOR = 0xFFFFFF55;
    private static final int BACKGROUND_COLOR = 0xA0101010;

    @Override
    public void render(GuiGraphicsExtractor guiGraphics, DeltaTracker deltaTracker) {
        if (ClientShopPromptState.get().isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null) {
            return; // Don't draw over an open menu/screen.
        }

        String keyName = SettlemyntsModClient.OPEN_NPC_MENU.getTranslatedKeyMessage().getString();
        String text = "Press " + keyName + " to talk";

        Font font = minecraft.font;
        int textWidth = font.width(text);
        int left = guiGraphics.guiWidth() / 2 - textWidth / 2 - PADDING;
        int right = left + textWidth + PADDING * 2;
        int bottom = guiGraphics.guiHeight() - MARGIN_FROM_BOTTOM;
        int top = bottom - font.lineHeight - PADDING * 2;

        guiGraphics.fill(left, top, right, bottom, BACKGROUND_COLOR);
        guiGraphics.text(font, text, left + PADDING, top + PADDING, TEXT_COLOR);
    }
}
