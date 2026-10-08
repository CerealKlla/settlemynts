package com.github.cerealklla.settlemynts.plotsign.client;

import java.util.List;

import com.github.cerealklla.settlemynts.plotsign.AddListingFromHeldItemPayload;
import com.github.cerealklla.settlemynts.plotsign.AdjustListingPayload;
import com.github.cerealklla.settlemynts.plotsign.BuyFromShopPayload;
import com.github.cerealklla.settlemynts.plotsign.OpenShopPayload;
import com.github.cerealklla.settlemynts.plotsign.SellToShopPayload;
import com.github.cerealklla.settlemynts.plotsign.ShopListingEntry;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * The real Shop screen (design doc Section 14a, 2026-10-05) -- replaces the old "Enter Shop"/
 * "Manage Shop" chat-message placeholder now that a real Yconomics-backed Shop exists (see
 * {@code bridge.YconomicsShopBridge}). One screen, two modes driven by {@link OpenShopPayload#manage()}:
 *
 * <p><b>Buy mode</b>: one row per listing (nice item name + price), a single "Buy" button (buys 1 --
 * the "Buy 10" option was removed 2026-10-05 per explicit request). The server charges the buyer's
 * own Gold Nugget balance only for however much stock was actually available (see {@code
 * BuyFromShopPayload}'s own doc) -- feedback is a chat message, no further screen state to track
 * client-side, so this screen never needs a round trip back to itself after a buy.
 *
 * <p><b>Manage mode</b> (owner/Town-Planner only, server-checked): the same rows gain "-10/-1/+1/+10"
 * price buttons and a "Remove" button, plus an "Add Listing (Held Item)" button at the bottom. Every
 * action re-requests the full listing (closing and reopening this same screen via a fresh {@link
 * OpenShopPayload}) rather than predicting the new state client-side -- simplest correct option.
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
    private static final int VIEWPORT_TOP = 50;
    private static final int PANEL_LEFT_MARGIN = 230;
    private static final int BACKGROUND_COLOR = 0xC0101010;

    private final OpenShopPayload data;
    private int scrollOffset;

    public ShopScreen(OpenShopPayload data) {
        super(Component.literal(data.manage() ? "Manage Shop" : "Shop"));
        this.data = data;
    }

    @Override
    protected void init() {
        scrollOffset = 0;
        rebuildShopWidgets();
    }

    private int footerTop() {
        return data.manage() ? height - 70 : height - 40;
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
            if (data.manage()) {
                addRenderableWidget(Button.builder(Component.literal("-10"), b -> adjust(listing, -10, false))
                        .bounds(centerX - 150, rowY, 30, 20).build());
                addRenderableWidget(Button.builder(Component.literal("-1"), b -> adjust(listing, -1, false))
                        .bounds(centerX - 118, rowY, 30, 20).build());
                addRenderableWidget(Button.builder(Component.literal("+1"), b -> adjust(listing, 1, false))
                        .bounds(centerX + 88, rowY, 30, 20).build());
                addRenderableWidget(Button.builder(Component.literal("+10"), b -> adjust(listing, 10, false))
                        .bounds(centerX + 120, rowY, 30, 20).build());
                addRenderableWidget(Button.builder(Component.literal("Remove"), b -> adjust(listing, 0, true))
                        .bounds(centerX + 155, rowY, 60, 20).build());
            } else {
                addRenderableWidget(Button.builder(Component.literal("Buy"), b -> buy(listing))
                        .bounds(centerX + 90, rowY, 60, 20).build());
                addRenderableWidget(Button.builder(Component.literal("Sell"), b -> sell(listing))
                        .bounds(centerX + 155, rowY, 60, 20).build());
            }
        }

        int y = footerTop + 6;
        if (data.manage()) {
            addRenderableWidget(Button.builder(Component.literal("Add Listing (Held Item)"), b -> addFromHeldItem())
                    .bounds(centerX - 110, y, 220, 20).build());
            y += 30;
        }

        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(centerX - 50, y, 100, 20).build());
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

    private void adjust(ShopListingEntry listing, int priceDelta, boolean remove) {
        send(new AdjustListingPayload(data.signPos(), listing.resourceKey(), listing.isTag(), priceDelta, remove));
        onClose();
    }

    private void addFromHeldItem() {
        send(new AddListingFromHeldItemPayload(data.signPos()));
        onClose();
    }

    private void send(CustomPacketPayload payload) {
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(payload));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int centerX = width / 2;
        graphics.fill(centerX - PANEL_LEFT_MARGIN, VIEWPORT_TOP - 4, centerX + PANEL_LEFT_MARGIN, footerTop() + 4, BACKGROUND_COLOR);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int centerX = width / 2;
        int titleWidth = font.width(title);
        graphics.text(font, title, centerX - titleWidth / 2, 20, 0xFFFFFFFF);

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
                String line = listing.label() + " -- Buy " + listing.pricePerUnit() + " / Sell "
                        + listing.buyPricePerUnit() + " nuggets/unit";
                graphics.text(font, line, centerX - PANEL_LEFT_MARGIN + 10, y + 6, 0xFFFFFFFF);
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
