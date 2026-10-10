package com.github.cerealklla.settlemynts.plotsign.client;

import java.util.List;

import com.github.cerealklla.settlemynts.plotsign.OpenShopWishlistPayload;
import com.github.cerealklla.settlemynts.plotsign.SellToShopWishlistPayload;
import com.github.cerealklla.settlemynts.plotsign.WishlistEntry;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

/**
 * "What do you need?" -- a shop's public wishlist screen (2026-10-10, see {@code
 * zone.ShopWishlist}'s own doc for the full feature). Same table/scroll shape as {@link ShopScreen}
 * (one row per deficit: Item Name | Needed | Player Stock | "Sell (Ng)" selling 1 unit per click at
 * the premium price), deliberately not merged with that screen since this is a different data
 * source (Planned Inventory deficits, not real Shop listings).
 *
 * <p>Selling repeatedly from this same open screen is deliberately unrestricted -- a time-limited
 * "ask again before each sale" gate existed briefly and was removed the same day, explicit feedback:
 * "the 'ask what he wants' blocking repeated selling is tedious." A successful "Sell" click
 * re-opens this same screen in place with fresh data (a now-smaller or vanished deficit row, and a
 * live-recomputed price), same refresh pattern {@link ShopScreen} uses for its own gold/stock
 * updates after a Buy/Sell.
 */
public final class WishlistScreen extends Screen {

    private static final int ROW_HEIGHT = 24;
    private static final int VIEWPORT_TOP = 72;
    private static final int PANEL_LEFT_MARGIN = 225;
    private static final int BACKGROUND_COLOR = 0xC0101010;

    private static final int COL_NAME_X = -PANEL_LEFT_MARGIN + 10;
    private static final int COL_NEEDED_X = -70;
    private static final int COL_PLAYER_STOCK_X = -10;
    private static final int SELL_BUTTON_X = 35;
    private static final int SELL_BUTTON_WIDTH = 90;

    private OpenShopWishlistPayload data;
    private int scrollOffset;

    public WishlistScreen(OpenShopWishlistPayload data) {
        super(Component.literal("What do you need?"));
        this.data = data;
    }

    /** Called by {@code SettlemyntsModClient} when a fresh {@code OpenShopWishlistPayload} arrives (after a "Sell" click) while this screen is already open. */
    public void refresh(OpenShopWishlistPayload data) {
        this.data = data;
        rebuildWishlistWidgets();
    }

    @Override
    protected void init() {
        scrollOffset = 0;
        rebuildWishlistWidgets();
    }

    private int footerTop() {
        return height - 40;
    }

    private int maxScrollOffset() {
        int viewport = footerTop() - VIEWPORT_TOP;
        return Math.max(0, data.entries().size() * ROW_HEIGHT - viewport);
    }

    private void rebuildWishlistWidgets() {
        clearWidgets();
        int centerX = width / 2;
        int footerTop = footerTop();
        List<WishlistEntry> entries = data.entries();

        for (int i = 0; i < entries.size(); i++) {
            WishlistEntry entry = entries.get(i);
            int rowY = VIEWPORT_TOP + i * ROW_HEIGHT - scrollOffset;
            if (rowY < VIEWPORT_TOP || rowY + ROW_HEIGHT > footerTop) {
                continue;
            }
            addRenderableWidget(Button.builder(Component.literal("Sell (" + entry.premiumPricePerUnit() + "g)"), b -> sell(entry))
                    .bounds(centerX + SELL_BUTTON_X, rowY, SELL_BUTTON_WIDTH, 20).build());
        }

        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(centerX - 50, footerTop + 6, 100, 20).build());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
            return true;
        }
        scrollOffset = Math.max(0, Math.min(maxScrollOffset(), scrollOffset - (int) (scrollY * ROW_HEIGHT)));
        rebuildWishlistWidgets();
        return true;
    }

    private void sell(WishlistEntry entry) {
        send(new SellToShopWishlistPayload(data.anchor(), entry.resourceKey(), entry.isTag(), 1));
    }

    private void send(CustomPacketPayload payload) {
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(payload));
    }

    private int playerStockOf(WishlistEntry entry) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return 0;
        }
        var inventory = player.getInventory();
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (entry.matches(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int centerX = width / 2;
        graphics.fill(centerX - PANEL_LEFT_MARGIN, VIEWPORT_TOP - 24, centerX + PANEL_LEFT_MARGIN, footerTop() + 4, BACKGROUND_COLOR);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int centerX = width / 2;
        int titleWidth = font.width(title);
        graphics.text(font, title, centerX - titleWidth / 2, 20, 0xFFFFFFFF);

        String subtitle = "Sell at a 10% premium -- prices and amounts needed update live as you sell.";
        graphics.text(font, subtitle, centerX - font.width(subtitle) / 2, 36, 0xFFFFD700);

        graphics.text(font, "Item", centerX + COL_NAME_X, VIEWPORT_TOP - 16, 0xFFAAAAAA);
        graphics.text(font, "Needed", centerX + COL_NEEDED_X, VIEWPORT_TOP - 16, 0xFFAAAAAA);
        graphics.text(font, "Player Stock", centerX + COL_PLAYER_STOCK_X, VIEWPORT_TOP - 16, 0xFFAAAAAA);

        List<WishlistEntry> entries = data.entries();
        int footerTop = footerTop();
        if (entries.isEmpty()) {
            String message = "This shop's preferred inventory is fully stocked.";
            graphics.text(font, message, centerX - font.width(message) / 2, VIEWPORT_TOP, 0xFFAAAAAA);
            return;
        }

        graphics.enableScissor(centerX - PANEL_LEFT_MARGIN, VIEWPORT_TOP, centerX + PANEL_LEFT_MARGIN, footerTop);
        int y = VIEWPORT_TOP - scrollOffset;
        for (WishlistEntry entry : entries) {
            if (y + ROW_HEIGHT >= VIEWPORT_TOP && y <= footerTop) {
                graphics.text(font, entry.label(), centerX + COL_NAME_X, y + 6, 0xFFFFFFFF);
                graphics.text(font, Integer.toString(entry.quantityNeeded()), centerX + COL_NEEDED_X, y + 6, 0xFFFFFFFF);
                graphics.text(font, Integer.toString(playerStockOf(entry)), centerX + COL_PLAYER_STOCK_X, y + 6, 0xFFFFFFFF);
            }
            y += ROW_HEIGHT;
        }
        graphics.disableScissor();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
