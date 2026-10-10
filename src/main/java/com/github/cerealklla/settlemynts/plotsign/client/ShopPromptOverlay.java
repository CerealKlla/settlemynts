package com.github.cerealklla.settlemynts.plotsign.client;

import com.github.cerealklla.settlemynts.SettlemyntsModClient;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.neoforged.neoforge.client.gui.GuiLayer;

/**
 * "Press &lt;hotkey&gt; to open shop" -- floating text centered over the player's XP bar while
 * within 4 blocks of a shop-eligible Plot Config Sign (design doc Section 14a, 2026-10-05). Driven
 * entirely by {@link ClientShopPromptState} (updated from {@code PlotShopPromptPayload}) -- no
 * per-frame server round-trip. Same render-from-client-state shape as {@code
 * zone.PlotValidityOverlay}, positioned instead right above vanilla's own XP bar (an eyeballed
 * offset, like every other HUD margin in this suite).
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

        String keyName = SettlemyntsModClient.OPEN_SHOP.getTranslatedKeyMessage().getString();
        String text = "Press " + keyName + " to open shop";
        // "Press <key> to chat" (2026-10-10, public shop wishlist) -- only shown when this shop
        // actually has something to ask about, see ClientShopPromptState#hasWishlist's own doc.
        String chatText = ClientShopPromptState.hasWishlist()
                ? "Press " + SettlemyntsModClient.TALK.getTranslatedKeyMessage().getString() + " to chat"
                : null;

        Font font = minecraft.font;
        int textWidth = Math.max(font.width(text), chatText != null ? font.width(chatText) : 0);
        int left = guiGraphics.guiWidth() / 2 - textWidth / 2 - PADDING;
        int right = left + textWidth + PADDING * 2;
        int lineCount = chatText != null ? 2 : 1;
        int bottom = guiGraphics.guiHeight() - MARGIN_FROM_BOTTOM;
        int top = bottom - font.lineHeight * lineCount - PADDING * 2;

        guiGraphics.fill(left, top, right, bottom, BACKGROUND_COLOR);
        guiGraphics.text(font, text, left + PADDING, top + PADDING, TEXT_COLOR);
        if (chatText != null) {
            guiGraphics.text(font, chatText, left + PADDING, top + PADDING + font.lineHeight, TEXT_COLOR);
        }
    }
}
