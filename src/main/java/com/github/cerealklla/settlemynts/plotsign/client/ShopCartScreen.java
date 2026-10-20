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
 * the flat background panel, column headers, per-row price/stock text next to the Shop column, the
 * two gold totals, the "Confirm Purchase" button, and (2026-10-20) the Shop column's scroll controls.
 *
 * <p><b>Fixed window height + real scrolling, 2026-10-20</b> -- real report against the first cut:
 * "need to fix the scaling, and definitely need the ability to scroll through shop items." The
 * screen's height no longer grows with the shop's listing count at all (that was the "scaling" bug --
 * a shop with 20+ listings rendered a window taller than the actual game window). The Shop column
 * now only ever shows {@link ShopCartMenu#VISIBLE_SHOP_ROWS} rows at a time; the mouse wheel (over
 * the Shop column) and the "^"/"v" buttons both scroll by sending {@link
 * ShopCartMenu#SCROLL_UP_BUTTON_ID}/{@link ShopCartMenu#SCROLL_DOWN_BUTTON_ID} the same way "Confirm
 * Purchase" already sends {@link ShopCartMenu#CONFIRM_BUTTON_ID} -- the server is the one authority
 * on which window is showing (see {@link ShopCartMenu}'s own doc for why), so this screen only ever
 * reads the current window back via {@link ShopCartMenu#scrollOffset()}, never computes it locally.
 */
public class ShopCartScreen extends AbstractContainerScreen<ShopCartMenu> {

    private static final int BACKGROUND_COLOR = 0xC0101010;
    private static final int TOP_Y = 18;
    private static final int ROW_HEIGHT = 18;
    private static final int SHOP_X = 8;
    // Widened 2026-10-20 -- real report, with a screenshot showing the Shop column's "N in stock
    // Pg / Sg" price text running directly into the Buy column's icons. Must stay in lockstep with
    // ShopCartMenu's own identical copies of these constants.
    private static final int BUY_X = 230;
    private static final int SELL_X = 260;
    private static final int PLAYER_INV_X = 300;
    private static final int SCROLLBAR_X = BUY_X - 16;

    public ShopCartScreen(ShopCartMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, PLAYER_INV_X + 9 * 18 + 10,
                TOP_Y + ShopCartMenu.VISIBLE_SHOP_ROWS * ROW_HEIGHT + 56);
        this.inventoryLabelY = -1000; // Suppress vanilla's auto "Inventory" label -- see class doc, player inv has no separate panel here.
    }

    @Override
    protected void init() {
        super.init();
        int confirmY = this.topPos + this.imageHeight - 44;
        addRenderableWidget(Button.builder(Component.literal("Confirm Purchase"),
                        b -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, ShopCartMenu.CONFIRM_BUTTON_ID))
                .bounds(this.leftPos + SHOP_X, confirmY, 150, 20).build());
        // "Auto Sell" (2026-10-20, explicit user request) -- queues everything the player is carrying
        // that the shop's Planned Inventory currently wants straight into the Sell column.
        addRenderableWidget(Button.builder(Component.literal("Auto Sell"),
                        b -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, ShopCartMenu.AUTO_SELL_BUTTON_ID))
                .bounds(this.leftPos + SHOP_X + 160, confirmY, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("^"),
                        b -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, ShopCartMenu.SCROLL_UP_BUTTON_ID))
                .bounds(this.leftPos + SCROLLBAR_X, this.topPos + TOP_Y, 12, 12).build());
        addRenderableWidget(Button.builder(Component.literal("v"),
                        b -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, ShopCartMenu.SCROLL_DOWN_BUTTON_ID))
                .bounds(this.leftPos + SCROLLBAR_X, this.topPos + TOP_Y + ShopCartMenu.VISIBLE_SHOP_ROWS * ROW_HEIGHT - 12, 12, 12).build());
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
        int offset = menu.scrollOffset();
        int visibleCount = Math.min(ShopCartMenu.VISIBLE_SHOP_ROWS, Math.max(0, listings.size() - offset));
        for (int row = 0; row < visibleCount; row++) {
            ShopListingEntry listing = listings.get(offset + row);
            int rowTextY = top + TOP_Y + row * ROW_HEIGHT + ROW_HEIGHT / 2 - 4;
            // Real request, 2026-10-20: "need to show the real inventory count available here" -- the
            // catalog slot's own icon always shows a full conjured max-size stack (it has to, for the
            // "infinite stack" pick-up UX), so the real current stock is drawn here instead.
            // The "wants N" planned-inventory-deficit suffix that used to sit here was removed per
            // explicit request the same day: "Don't show the preferred count on the shop, players
            // don't need to know that" -- a plot's own Planned Inventory target is the owner's
            // private restocking plan, not public shopper-facing info (same treatment this project
            // already gives SuppressedShopResource/Manage Shop internals elsewhere). The underlying
            // mechanism (ShopCartMenu.SellSlot's gate, confirmShopCart's cap) is completely
            // unaffected -- only this display line changed.
            String priceLine = listing.shopStock() + " in stock   " + listing.pricePerUnit() + "g / " + listing.buyPricePerUnit() + "g";
            graphics.text(font, priceLine, left + SHOP_X + 20, rowTextY, 0xFFFFFFFF);
        }
        if (listings.size() > ShopCartMenu.VISIBLE_SHOP_ROWS) {
            String scrollLabel = (offset + 1) + "-" + (offset + visibleCount) + " / " + listings.size();
            graphics.text(font, scrollLabel, left + SHOP_X, top + TOP_Y + ShopCartMenu.VISIBLE_SHOP_ROWS * ROW_HEIGHT + 2, 0xFFAAAAAA);
        }

        int confirmY = this.topPos + this.imageHeight - 44;
        String hint = "Shop <-> Buy, and Player <-> Sell only -- Confirm Purchase to charge/pay for everything queued.";
        graphics.text(font, Component.literal(hint).withStyle(ChatFormatting.GRAY), left + SHOP_X, confirmY - 12, 0xFFAAAAAA);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0 && mouseX >= this.leftPos + SHOP_X && mouseX < this.leftPos + BUY_X
                && mouseY >= this.topPos + TOP_Y && mouseY < this.topPos + TOP_Y + ShopCartMenu.VISIBLE_SHOP_ROWS * ROW_HEIGHT) {
            int buttonId = scrollY > 0 ? ShopCartMenu.SCROLL_UP_BUTTON_ID : ShopCartMenu.SCROLL_DOWN_BUTTON_ID;
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, buttonId);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * Real report, 2026-10-20, with a screenshot of a Shop catalog item dropped on the ground:
     * "you are able to pick up a shop item and drop it on the ground by clicking outside the trade
     * window... citing the methodology used for the Kyt editor screen" -- that's exactly the fix
     * this is. Vanilla's own {@code AbstractContainerScreen#mouseClicked}/{@code mouseReleased} both
     * compute "did this click land outside the panel" via this exact method, and send the
     * server-authoritative {@code SLOT_CLICKED_OUTSIDE} (-999) sentinel for one when it did --
     * {@code AbstractContainerMenu#doClick} handles that sentinel by unconditionally calling {@code
     * player.drop(getCarried(), true)}, a direct world-item spawn with no conjured-item awareness at
     * all, completely bypassing {@code ShopCartMenu#removed}'s own discard logic (which only ever
     * runs when the screen actually *closes*, not on every click). Kyt's own
     * {@code loadout.client.LoadoutEditorScreen} hit this identical bug and fixed it exactly this
     * way: forcing this to always return {@code false} is safe, not just convenient -- confirmed
     * there against the decompiled {@code AbstractContainerMenu#doClick} source that every
     * non-{@code -999} branch already starts with {@code if (slotIndex < 0) return;}, so suppressing
     * the -999 sentinel entirely still leaves a completely safe no-op for any click/release that
     * isn't over a real {@link net.minecraft.world.inventory.Slot} -- exactly ordinary vanilla
     * behavior for clicking dead background space *inside* a normal container screen's own panel.
     */
    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int xo, int yo) {
        return false;
    }
}
