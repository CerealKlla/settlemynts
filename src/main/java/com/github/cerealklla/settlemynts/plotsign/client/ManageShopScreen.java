package com.github.cerealklla.settlemynts.plotsign.client;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.github.cerealklla.settlemynts.plotsign.OpenShopPayload;
import com.github.cerealklla.settlemynts.plotsign.SetShopListingsPayload;
import com.github.cerealklla.settlemynts.plotsign.ShopInventoryEntry;
import com.github.cerealklla.settlemynts.plotsign.ShopListingUpdate;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.Identifier;

/**
 * Manage Shop screen (2026-10-08, split out of {@code ShopScreen} -- real feedback: "having to close
 * the UI, put something in your hand, then go to the manage screen to add an item is clunky"). One
 * row per {@link ShopInventoryEntry} -- every unique item physically sitting in the plot's own boxes
 * right now, plus any existing listing not currently backed by box stock -- each with a direct
 * numeric "Sell Cost" {@link EditBox} instead of a separate held-item-add flow or +/-1/+/-10 buttons
 * (both already replaced once this session, see {@code ShopListingEntry}'s own history). Blank/zero
 * means "don't list it"; "Save Changes" sends every row in one {@link SetShopListingsPayload} batch.
 * A never-yet-listed row is pre-filled with {@link ShopInventoryEntry#suggestedPrice()} (the Zone
 * Type's catalog recommendation, if any) rather than a real price -- see that field's own doc for
 * why this replaced the old always-on catalog auto-listing (real bug: an owner's explicit removal
 * kept getting silently reinstated on the next open).
 *
 * <p><b>Why edited values are kept in {@link #pendingValues} rather than just read off the live
 * {@link EditBox} widgets</b>: this screen uses the same scissor-clip scroll pattern as {@code
 * ShopScreen} (row widgets only exist while their row is fully inside the viewport -- see that
 * class's own doc for why). A plain {@code EditBox} is a widget like any button, so scrolling a row
 * off-screen destroys it; unlike a button, destroying an EditBox would silently throw away whatever
 * the player had already typed into it. Every keystroke is mirrored into this map via {@code
 * setResponder}, and a row's EditBox is always re-primed from the map (not from the original
 * server-sent price) whenever it's rebuilt, so scrolling away and back never loses an edit.
 */
public final class ManageShopScreen extends Screen {

    private static final int ROW_HEIGHT = 24;
    private static final int VIEWPORT_TOP = 60;
    private static final int PANEL_LEFT_MARGIN = 220;
    private static final int BACKGROUND_COLOR = 0xC0101010;

    private final OpenShopPayload data;
    private final Map<Identifier, String> pendingValues = new LinkedHashMap<>();
    private int scrollOffset;

    public ManageShopScreen(OpenShopPayload data) {
        super(Component.literal("Manage Shop"));
        this.data = data;
        for (ShopInventoryEntry entry : data.inventory()) {
            int prefill = entry.listedPrice() > 0 ? entry.listedPrice() : entry.suggestedPrice();
            pendingValues.put(entry.resourceKey(), prefill > 0 ? Integer.toString(prefill) : "");
        }
    }

    @Override
    protected void init() {
        scrollOffset = 0;
        rebuildManageWidgets();
    }

    private int footerTop() {
        return height - 70;
    }

    private int maxScrollOffset() {
        int viewport = footerTop() - VIEWPORT_TOP;
        return Math.max(0, data.inventory().size() * ROW_HEIGHT - viewport);
    }

    private void rebuildManageWidgets() {
        clearWidgets();
        int centerX = width / 2;
        int footerTop = footerTop();

        List<ShopInventoryEntry> inventory = data.inventory();
        for (int i = 0; i < inventory.size(); i++) {
            ShopInventoryEntry entry = inventory.get(i);
            int rowY = VIEWPORT_TOP + i * ROW_HEIGHT - scrollOffset;
            if (rowY < VIEWPORT_TOP || rowY + ROW_HEIGHT > footerTop) {
                continue; // Not fully inside the viewport this frame -- see class doc.
            }
            EditBox priceBox = new EditBox(font, centerX + 120, rowY, 90, 20, Component.literal("Sell Cost"));
            priceBox.setMaxLength(9);
            priceBox.setFilter(s -> s.isEmpty() || s.chars().allMatch(Character::isDigit));
            priceBox.setValue(pendingValues.getOrDefault(entry.resourceKey(), ""));
            priceBox.setResponder(value -> pendingValues.put(entry.resourceKey(), value));
            addRenderableWidget(priceBox);
        }

        int y = footerTop + 6;
        addRenderableWidget(Button.builder(Component.literal("Save Changes"), b -> saveChanges())
                .bounds(centerX - 155, y, 150, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(centerX + 5, y, 150, 20).build());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
            return true;
        }
        scrollOffset = Math.max(0, Math.min(maxScrollOffset(), scrollOffset - (int) (scrollY * ROW_HEIGHT)));
        rebuildManageWidgets();
        return true;
    }

    private void saveChanges() {
        List<ShopListingUpdate> updates = data.inventory().stream()
                .map(entry -> {
                    String raw = pendingValues.getOrDefault(entry.resourceKey(), "").trim();
                    int price = raw.isEmpty() ? 0 : Integer.parseInt(raw);
                    return new ShopListingUpdate(entry.resourceKey(), entry.isTag(), price);
                })
                .toList();
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(
                new SetShopListingsPayload(data.signPos(), updates)));
        // Closes outright rather than reopening this same screen with its now-stale data -- same
        // "action then close, reopen the sign for fresh state" convention this feature area already
        // settled on (see ShopScreen's own doc).
        Minecraft.getInstance().setScreen(null);
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

        graphics.text(font, "Item", centerX - PANEL_LEFT_MARGIN + 10, VIEWPORT_TOP - 16, 0xFFAAAAAA);
        graphics.text(font, "Shop Stock", centerX + 10, VIEWPORT_TOP - 16, 0xFFAAAAAA);
        graphics.text(font, "Sell Cost", centerX + 120, VIEWPORT_TOP - 16, 0xFFAAAAAA);

        List<ShopInventoryEntry> inventory = data.inventory();
        int footerTop = footerTop();
        if (inventory.isEmpty()) {
            String message = "No items found in this plot's boxes.";
            graphics.text(font, message, centerX - font.width(message) / 2, VIEWPORT_TOP, 0xFFAAAAAA);
            return;
        }

        graphics.enableScissor(centerX - PANEL_LEFT_MARGIN, VIEWPORT_TOP, centerX + PANEL_LEFT_MARGIN, footerTop);
        int y = VIEWPORT_TOP - scrollOffset;
        for (ShopInventoryEntry entry : inventory) {
            if (y + ROW_HEIGHT >= VIEWPORT_TOP && y <= footerTop) {
                graphics.text(font, entry.label(), centerX - PANEL_LEFT_MARGIN + 10, y + 6, 0xFFFFFFFF);
                graphics.text(font, Integer.toString(entry.shopStock()), centerX + 10, y + 6, 0xFFFFFFFF);
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
