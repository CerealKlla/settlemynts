package com.github.cerealklla.settlemynts.plotsign.client;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.github.cerealklla.settlemynts.plotsign.OpenPlannedInventoryPayload;
import com.github.cerealklla.settlemynts.plotsign.PlannedInventoryEntry;
import com.github.cerealklla.settlemynts.plotsign.PlannedInventoryUpdate;
import com.github.cerealklla.settlemynts.plotsign.SetPlannedInventoryPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.Identifier;

/**
 * "Planned Inventory" screen (2026-10-09, explicit user request) -- lets a plot owner declare each
 * resource's own "happy state" target stock level, consumed by {@code zone.PlannedInventoryClearing}'s
 * nightly inter-plot trading pass. Mirrors {@code ManageShopScreen}'s scan-the-plot bulk editor layout
 * exactly (same scissor-clip scroll pattern, same {@link #pendingValues}-backed EditBox persistence
 * across scroll -- see that class's own doc for the full reasoning), with a "Target Qty" column
 * instead of "Sell Cost": a blank box means "not managed by Planned Inventory at all," a typed
 * non-negative integer (including "0") means "managed at exactly this target" -- see {@link
 * PlannedInventoryEntry}'s own doc for why blank and zero are deliberately different states here.
 */
public final class PlannedInventoryScreen extends Screen {

    private static final int ROW_HEIGHT = 24;
    private static final int VIEWPORT_TOP = 60;
    private static final int PANEL_LEFT_MARGIN = 220;
    private static final int BACKGROUND_COLOR = 0xC0101010;

    private final OpenPlannedInventoryPayload data;
    private final Map<Identifier, String> pendingValues = new LinkedHashMap<>();
    private int scrollOffset;

    public PlannedInventoryScreen(OpenPlannedInventoryPayload data) {
        super(Component.literal("Planned Inventory"));
        this.data = data;
        for (PlannedInventoryEntry entry : data.entries()) {
            pendingValues.put(entry.resourceKey(), entry.targetCount() >= 0 ? Integer.toString(entry.targetCount()) : "");
        }
    }

    @Override
    protected void init() {
        scrollOffset = 0;
        rebuildPlannedInventoryWidgets();
    }

    private int footerTop() {
        return height - 70;
    }

    private int maxScrollOffset() {
        int viewport = footerTop() - VIEWPORT_TOP;
        return Math.max(0, data.entries().size() * ROW_HEIGHT - viewport);
    }

    private void rebuildPlannedInventoryWidgets() {
        clearWidgets();
        int centerX = width / 2;
        int footerTop = footerTop();

        List<PlannedInventoryEntry> entries = data.entries();
        for (int i = 0; i < entries.size(); i++) {
            PlannedInventoryEntry entry = entries.get(i);
            int rowY = VIEWPORT_TOP + i * ROW_HEIGHT - scrollOffset;
            if (rowY < VIEWPORT_TOP || rowY + ROW_HEIGHT > footerTop) {
                continue; // Not fully inside the viewport this frame -- see class doc.
            }
            EditBox targetBox = new EditBox(font, centerX + 120, rowY, 90, 20, Component.literal("Target Qty"));
            targetBox.setMaxLength(9);
            targetBox.setFilter(s -> s.isEmpty() || s.chars().allMatch(Character::isDigit));
            targetBox.setValue(pendingValues.getOrDefault(entry.resourceKey(), ""));
            targetBox.setResponder(value -> pendingValues.put(entry.resourceKey(), value));
            addRenderableWidget(targetBox);
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
        rebuildPlannedInventoryWidgets();
        return true;
    }

    private void saveChanges() {
        List<PlannedInventoryUpdate> updates = data.entries().stream()
                .map(entry -> {
                    String raw = pendingValues.getOrDefault(entry.resourceKey(), "").trim();
                    boolean managed = !raw.isEmpty();
                    int target = managed ? Integer.parseInt(raw) : 0;
                    return new PlannedInventoryUpdate(entry.resourceKey(), entry.isTag(), managed, target);
                })
                .toList();
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(
                new SetPlannedInventoryPayload(data.signPos(), updates)));
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
        graphics.text(font, "Current Stock", centerX + 10, VIEWPORT_TOP - 16, 0xFFAAAAAA);
        graphics.text(font, "Target Qty", centerX + 120, VIEWPORT_TOP - 16, 0xFFAAAAAA);

        List<PlannedInventoryEntry> entries = data.entries();
        int footerTop = footerTop();
        if (entries.isEmpty()) {
            String message = "No items found in this plot's boxes.";
            graphics.text(font, message, centerX - font.width(message) / 2, VIEWPORT_TOP, 0xFFAAAAAA);
            return;
        }

        graphics.enableScissor(centerX - PANEL_LEFT_MARGIN, VIEWPORT_TOP, centerX + PANEL_LEFT_MARGIN, footerTop);
        int y = VIEWPORT_TOP - scrollOffset;
        for (PlannedInventoryEntry entry : entries) {
            if (y + ROW_HEIGHT >= VIEWPORT_TOP && y <= footerTop) {
                graphics.text(font, entry.label(), centerX - PANEL_LEFT_MARGIN + 10, y + 6, 0xFFFFFFFF);
                graphics.text(font, Integer.toString(entry.currentStock()), centerX + 10, y + 6, 0xFFFFFFFF);
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
