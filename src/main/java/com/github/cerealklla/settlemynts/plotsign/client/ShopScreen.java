package com.github.cerealklla.settlemynts.plotsign.client;

import java.util.List;

import com.github.cerealklla.settlemynts.plotsign.BuyFromShopPayload;
import com.github.cerealklla.settlemynts.plotsign.OpenShopPayload;
import com.github.cerealklla.settlemynts.plotsign.SellToShopPayload;
import com.github.cerealklla.settlemynts.plotsign.ShopListingEntry;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

/**
 * The real Shop screen, Buy mode only (design doc Section 14a, 2026-10-05; split out of Manage mode
 * into {@code ManageShopScreen} 2026-10-08, since the two now have genuinely different data shapes --
 * see that class's own doc). One row per listing, rendered as a table (real request, 2026-10-08):
 * Item Name | Shop Stock | Player Stock | "Buy (N)" | "Sell (N)", the button labels showing the real
 * per-unit price so a player never has to click to find out -- replaces the old plain "Buy"/"Sell"
 * labels plus a separate text line. The listed price is always the raw, un-adjusted listing price
 * (real question, 2026-10-08: "are the prices listed in here after being modified by player merchant
 * skills or before?") -- when this player's Merchant-skill bonus currently makes a real difference,
 * a second number in green is appended showing what they'd actually pay/receive right now (see
 * {@link ShopListingEntry#effectiveBuyPrice()}/{@link ShopListingEntry#effectiveSellPrice()}'s own
 * doc), e.g. "Buy (5) (4)".
 *
 * <p>"Merchant Gold: #    Player Gold: #" (added 2026-10-08, explicit request) is drawn once above
 * the table -- the shop's own Gold Nugget stock ({@link OpenShopPayload#shopGoldNuggets()}, relevant
 * to whether a "Sell" can actually be paid out) and the viewing player's own balance ({@link
 * OpenShopPayload#playerGoldNuggets()}, loose inventory + Coin Purse).
 *
 * <p>"Shop Stock" is server-computed ({@link ShopListingEntry#shopStock()}, a snapshot as of when the
 * screen opened -- not live-updating while it's open, same as every other field here). "Player Stock"
 * is computed fresh every frame from the client's own inventory ({@link ShopListingEntry#matches}),
 * so it never goes stale while the screen is open even as the player buys/sells.
 *
 * <p>The server charges the buyer's own Gold Nugget balance only for however much stock was actually
 * available -- feedback is a chat message, no further screen state to track client-side, so this
 * screen never needs a round trip back to itself after a buy or sell.
 *
 * <p><b>Scrolling + solid background, 2026-10-05</b> (real report: listings ran off the bottom of
 * the screen with no way to reach them, and had no backing panel to read against) -- same
 * pixel-offset scissor-clip scroll pattern as {@code skill.client.SkillsScreen} in Lyfe (mouse wheel,
 * {@code enableScissor}/{@code disableScissor} around the text pass). Row *widgets* (the real
 * {@code Button}s) aren't scissor-clippable the way drawn text is, so a row's buttons are only ever
 * added when the row sits fully inside the viewport -- a row scrolled halfway off an edge shows its
 * (clipped) text but no buttons that frame, which is an acceptable, rare transient rather than a
 * click-through-the-footer bug.
 */
public final class ShopScreen extends Screen {

    private static final int ROW_HEIGHT = 24;
    private static final int VIEWPORT_TOP = 72;
    private static final int PANEL_LEFT_MARGIN = 310;
    private static final int BACKGROUND_COLOR = 0xC0101010;

    private static final int COL_NAME_X = -PANEL_LEFT_MARGIN + 10;
    private static final int COL_SHOP_STOCK_X = -40;
    private static final int COL_PLAYER_STOCK_X = 50;

    private final OpenShopPayload data;
    private int scrollOffset;

    public ShopScreen(OpenShopPayload data) {
        super(Component.literal("Shop"));
        this.data = data;
    }

    @Override
    protected void init() {
        scrollOffset = 0;
        rebuildShopWidgets();
    }

    private int footerTop() {
        return height - 40;
    }

    private int maxScrollOffset() {
        int viewport = footerTop() - VIEWPORT_TOP;
        return Math.max(0, data.listings().size() * ROW_HEIGHT - viewport);
    }

    private void rebuildShopWidgets() {
        clearWidgets();
        int centerX = width / 2;
        int footerTop = footerTop();

        List<ShopListingEntry> listings = data.listings();
        for (int i = 0; i < listings.size(); i++) {
            ShopListingEntry listing = listings.get(i);
            int rowY = VIEWPORT_TOP + i * ROW_HEIGHT - scrollOffset;
            if (rowY < VIEWPORT_TOP || rowY + ROW_HEIGHT > footerTop) {
                continue; // Not fully inside the viewport this frame -- see class doc.
            }
            addRenderableWidget(Button.builder(priceLabel("Buy", listing.pricePerUnit(), listing.effectiveBuyPrice()), b -> buy(listing))
                    .bounds(centerX + 90, rowY, 100, 20).build());
            addRenderableWidget(Button.builder(priceLabel("Sell", listing.buyPricePerUnit(), listing.effectiveSellPrice()), b -> sell(listing))
                    .bounds(centerX + 195, rowY, 100, 20).build());
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
        rebuildShopWidgets();
        return true;
    }

    private void buy(ShopListingEntry listing) {
        send(new BuyFromShopPayload(data.signPos(), listing.resourceKey(), listing.isTag(), 1));
    }

    private void sell(ShopListingEntry listing) {
        send(new SellToShopPayload(data.signPos(), listing.resourceKey(), listing.isTag(), 1));
    }

    private void send(CustomPacketPayload payload) {
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(payload));
    }

    /** "Buy (5)", or "Buy (5) (4)" in green when this player's Merchant bonus makes the real price different -- see class doc. */
    private static Component priceLabel(String verb, int rawPrice, int effectivePrice) {
        Component label = Component.literal(verb + " (" + rawPrice + ")");
        if (effectivePrice != rawPrice) {
            label = label.copy().append(Component.literal(" (" + effectivePrice + ")").withStyle(ChatFormatting.GREEN));
        }
        return label;
    }

    private int playerStockOf(ShopListingEntry listing) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return 0;
        }
        var inventory = player.getInventory();
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (listing.matches(stack)) {
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

        String goldLine = "Merchant Gold: " + data.shopGoldNuggets() + "    Player Gold: " + data.playerGoldNuggets();
        graphics.text(font, goldLine, centerX - font.width(goldLine) / 2, 36, 0xFFFFD700);

        graphics.text(font, "Item", centerX + COL_NAME_X, VIEWPORT_TOP - 16, 0xFFAAAAAA);
        graphics.text(font, "Shop Stock", centerX + COL_SHOP_STOCK_X, VIEWPORT_TOP - 16, 0xFFAAAAAA);
        graphics.text(font, "Player Stock", centerX + COL_PLAYER_STOCK_X, VIEWPORT_TOP - 16, 0xFFAAAAAA);

        List<ShopListingEntry> listings = data.listings();
        int footerTop = footerTop();
        if (listings.isEmpty()) {
            String message = "Nothing for sale here yet.";
            graphics.text(font, message, centerX - font.width(message) / 2, VIEWPORT_TOP, 0xFFAAAAAA);
            return;
        }

        graphics.enableScissor(centerX - PANEL_LEFT_MARGIN, VIEWPORT_TOP, centerX + PANEL_LEFT_MARGIN, footerTop);
        int y = VIEWPORT_TOP - scrollOffset;
        for (ShopListingEntry listing : listings) {
            if (y + ROW_HEIGHT >= VIEWPORT_TOP && y <= footerTop) {
                graphics.text(font, listing.label(), centerX + COL_NAME_X, y + 6, 0xFFFFFFFF);
                graphics.text(font, Integer.toString(listing.shopStock()), centerX + COL_SHOP_STOCK_X, y + 6, 0xFFFFFFFF);
                graphics.text(font, Integer.toString(playerStockOf(listing)), centerX + COL_PLAYER_STOCK_X, y + 6, 0xFFFFFFFF);
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
