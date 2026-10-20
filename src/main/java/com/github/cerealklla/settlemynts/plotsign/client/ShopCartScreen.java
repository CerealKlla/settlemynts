package com.github.cerealklla.settlemynts.plotsign.client;

import com.github.cerealklla.settlemynts.plotsign.ShopCartMenu;
import com.github.cerealklla.settlemynts.plotsign.ShopListingEntry;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * The redesigned real Shop screen (2026-10-20), replacing the earlier button-list {@code ShopScreen}
 * shipped hours earlier the same day -- see {@link ShopCartMenu}'s own doc for the actual four-lane
 * container design this just renders. A real {@link AbstractContainerScreen}, so vanilla's own
 * generic slot rendering/click/drag/tooltip handling does almost everything; this class only draws
 * the flat background panel, column headers, per-row price/label text next to the Shop column, the
 * two gold totals, and the "Confirm Purchase" button.
 *
 * <p><b>Known limitation, not yet addressed</b>: no scrolling -- every listing gets its own row in
 * the Shop/Buy/Sell columns with no cap, so a shop with many listings will render rows past the
 * bottom of the window. The old {@code ShopScreen} had real scroll support; this one doesn't yet,
 * flagged for a follow-up rather than silently truncating (which would hide real stock from
 * purchase) or guessed at blind.
 */
public class ShopCartScreen extends AbstractContainerScreen<ShopCartMenu> {

    private static final int BACKGROUND_COLOR = 0xC0101010;
    private static final int TOP_Y = 18;
    private static final int ROW_HEIGHT = 18;
    private static final int SHOP_X = 8;
    private static final int BUY_X = 62;
    private static final int SELL_X = 98;
    private static final int PLAYER_INV_X = 138;

    public ShopCartScreen(ShopCartMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, PLAYER_INV_X + 9 * 18 + 10,
                TOP_Y + Math.max(4 * ROW_HEIGHT + 24, Math.max(1, menu.listings().size()) * ROW_HEIGHT) + 56);
        this.inventoryLabelY = -1000; // Suppress vanilla's auto "Inventory" label -- see class doc, player inv has no separate panel here.
    }

    @Override
    protected void init() {
        super.init();
        int confirmY = this.topPos + this.imageHeight - 44;
        addRenderableWidget(Button.builder(Component.literal("Confirm Purchase"),
                        b -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, ShopCartMenu.CONFIRM_BUTTON_ID))
                .bounds(this.leftPos + SHOP_X, confirmY, 150, 20).build());
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(this.leftPos, this.topPos, this.leftPos + this.imageWidth, this.topPos + this.imageHeight, BACKGROUND_COLOR);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int left = this.leftPos;
        int top = this.topPos;

        graphics.text(font, "Shop", left + SHOP_X, top + 4, 0xFFAAAAAA);
        graphics.text(font, "Buy", left + BUY_X, top + 4, 0xFFAAAAAA);
        graphics.text(font, "Sell", left + SELL_X, top + 4, 0xFFAAAAAA);

        String goldLine = "Merchant Gold: " + menu.shopGoldNuggets() + "    Player Gold: " + menu.playerGoldNuggets();
        graphics.text(font, goldLine, left + PLAYER_INV_X, top + 4, 0xFFFFD700);

        var listings = menu.listings();
        for (int i = 0; i < listings.size(); i++) {
            ShopListingEntry listing = listings.get(i);
            int rowTextY = top + TOP_Y + i * ROW_HEIGHT + ROW_HEIGHT / 2 - 4;
            String priceLine = listing.pricePerUnit() + "g / " + listing.buyPricePerUnit() + "g";
            graphics.text(font, priceLine, left + SHOP_X + 20, rowTextY, 0xFFFFFFFF);
        }

        int confirmY = this.topPos + this.imageHeight - 44;
        String hint = "Shop <-> Buy, and Player <-> Sell only -- Confirm Purchase to charge/pay for everything queued.";
        graphics.text(font, Component.literal(hint).withStyle(ChatFormatting.GRAY), left + SHOP_X, confirmY - 12, 0xFFAAAAAA);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
