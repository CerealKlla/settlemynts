package com.github.cerealklla.settlemynts.plotsign.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.github.cerealklla.nuclyus.menu.client.NuclyusMenu;
import com.github.cerealklla.nuclyus.menu.client.NuclyusMenuScreen;
import com.github.cerealklla.settlemynts.plotsign.ClientPlotSignRequests;
import com.github.cerealklla.settlemynts.plotsign.OpenPlannedInventoryPayload;
import com.github.cerealklla.settlemynts.plotsign.OpenPlotConfigSignMenuPayload;
import com.github.cerealklla.settlemynts.plotsign.PlannedInventoryEntry;
import com.github.cerealklla.settlemynts.plotsign.PlannedInventoryUpdate;
import com.github.cerealklla.settlemynts.plotsign.RequestPlannedInventoryPayload;
import com.github.cerealklla.settlemynts.plotsign.SetPlannedInventoryPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * "Planned Inventory" tab (tabbed Plot Config Sign rework, 2026-10-23) -- only present when {@code
 * canManage} and this plot has a real Shop. Folds the old flat {@code client.PlannedInventoryScreen}
 * in as tab content, same scissor-clip/EditBox/{@link #pendingValues} pattern as {@link ShopTab}'s
 * Manage-Shop table (see that class's own doc for the full reasoning).
 */
public final class PlannedInventoryTab implements NuclyusMenu {

    private static final int ROW_HEIGHT = 24;
    private static final int PANEL_HALF_WIDTH = 220;

    private final OpenPlotConfigSignMenuPayload data;
    private NuclyusMenuScreen host;
    private OpenPlannedInventoryPayload inventoryData;
    private final Map<Identifier, String> pendingValues = new LinkedHashMap<>();
    private final List<EditBox> rowWidgets = new ArrayList<>();
    private int scrollOffset;
    private Button saveButton;

    public PlannedInventoryTab(OpenPlotConfigSignMenuPayload data) {
        this.data = data;
    }

    @Override
    public Component title() {
        return Component.literal("Planned Inventory");
    }

    @Override
    public int order() {
        return 3;
    }

    @Override
    public void attachHost(Screen host) {
        this.host = (NuclyusMenuScreen) host;
    }

    @Override
    public void onShown() {
        inventoryData = null;
        pendingValues.clear();
        scrollOffset = 0;
        send(new RequestPlannedInventoryPayload(data.signPos()));

        Rect2i area = host.contentArea();
        saveButton = host.addMenuWidget(Button.builder(Component.literal("Save Changes"), b -> saveChanges())
                .bounds(area.getX() + area.getWidth() / 2 - 75, footerTop(area) + 6, 150, 20).build());
    }

    @Override
    public void onHidden() {
        clearRowWidgets();
        if (saveButton != null) {
            host.removeMenuWidget(saveButton);
            saveButton = null;
        }
    }

    private int viewportTop(Rect2i area) {
        return area.getY() + 40;
    }

    private int footerTop(Rect2i area) {
        return area.getY() + area.getHeight() - 30;
    }

    private int maxScrollOffset(Rect2i area) {
        if (inventoryData == null) {
            return 0;
        }
        int viewport = footerTop(area) - viewportTop(area);
        return Math.max(0, inventoryData.entries().size() * ROW_HEIGHT - viewport);
    }

    private void clearRowWidgets() {
        for (EditBox box : rowWidgets) {
            host.removeMenuWidget(box);
        }
        rowWidgets.clear();
    }

    private void rebuildRows(Rect2i area) {
        clearRowWidgets();
        if (inventoryData == null) {
            return;
        }
        int centerX = area.getX() + area.getWidth() / 2;
        int viewportTop = viewportTop(area);
        int footerTop = footerTop(area);
        List<PlannedInventoryEntry> entries = inventoryData.entries();
        for (int i = 0; i < entries.size(); i++) {
            PlannedInventoryEntry entry = entries.get(i);
            int rowY = viewportTop + i * ROW_HEIGHT - scrollOffset;
            if (rowY < viewportTop || rowY + ROW_HEIGHT > footerTop) {
                continue;
            }
            EditBox targetBox = new EditBox(Minecraft.getInstance().font, centerX + 120, rowY, 90, 20, Component.literal("Target Qty"));
            targetBox.setMaxLength(9);
            targetBox.setFilter(s -> s.isEmpty() || s.chars().allMatch(Character::isDigit));
            targetBox.setValue(pendingValues.getOrDefault(entry.resourceKey(), ""));
            targetBox.setResponder(value -> pendingValues.put(entry.resourceKey(), value));
            host.addMenuWidget(targetBox);
            rowWidgets.add(targetBox);
        }
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, Rect2i area, int mouseX, int mouseY, float partialTick) {
        if (inventoryData == null) {
            ClientPlotSignRequests.takePendingPlannedInventory().ifPresent(payload -> {
                inventoryData = payload;
                for (PlannedInventoryEntry entry : inventoryData.entries()) {
                    pendingValues.put(entry.resourceKey(), entry.targetCount() >= 0 ? Integer.toString(entry.targetCount()) : "");
                }
                rebuildRows(area);
            });
        }

        Font font = Minecraft.getInstance().font;
        int centerX = area.getX() + area.getWidth() / 2;
        int viewportTop = viewportTop(area);
        int footerTop = footerTop(area);
        if (inventoryData == null) {
            String message = "Loading...";
            graphics.text(font, message, centerX - font.width(message) / 2, viewportTop, 0xFFAAAAAA);
            return;
        }

        graphics.fill(centerX - PANEL_HALF_WIDTH, viewportTop - 16, centerX + PANEL_HALF_WIDTH, footerTop + 4, 0xC0101010);
        graphics.text(font, "Item", centerX - PANEL_HALF_WIDTH + 10, viewportTop - 14, 0xFFAAAAAA);
        graphics.text(font, "Current Stock", centerX + 10, viewportTop - 14, 0xFFAAAAAA);
        graphics.text(font, "Target Qty", centerX + 120, viewportTop - 14, 0xFFAAAAAA);

        List<PlannedInventoryEntry> entries = inventoryData.entries();
        if (entries.isEmpty()) {
            String message = "No items found in this plot's boxes.";
            graphics.text(font, message, centerX - font.width(message) / 2, viewportTop, 0xFFAAAAAA);
            return;
        }

        graphics.enableScissor(centerX - PANEL_HALF_WIDTH, viewportTop, centerX + PANEL_HALF_WIDTH, footerTop);
        int y = viewportTop - scrollOffset;
        for (PlannedInventoryEntry entry : entries) {
            if (y + ROW_HEIGHT >= viewportTop && y <= footerTop) {
                graphics.text(font, entry.label(), centerX - PANEL_HALF_WIDTH + 10, y + 6, 0xFFFFFFFF);
                graphics.text(font, Integer.toString(entry.currentStock()), centerX + 10, y + 6, 0xFFFFFFFF);
            }
            y += ROW_HEIGHT;
        }
        graphics.disableScissor();
    }

    @Override
    public boolean mouseScrolled(Rect2i area, double mouseX, double mouseY, double scrollX, double scrollY) {
        if (inventoryData == null) {
            return false;
        }
        scrollOffset = Math.max(0, Math.min(maxScrollOffset(area), scrollOffset - (int) (scrollY * ROW_HEIGHT)));
        rebuildRows(area);
        return true;
    }

    private void saveChanges() {
        if (inventoryData == null) {
            return;
        }
        List<PlannedInventoryUpdate> updates = inventoryData.entries().stream()
                .map(entry -> {
                    String raw = pendingValues.getOrDefault(entry.resourceKey(), "").trim();
                    boolean managed = !raw.isEmpty();
                    int target = managed ? Integer.parseInt(raw) : 0;
                    return new PlannedInventoryUpdate(entry.resourceKey(), entry.isTag(), managed, target);
                })
                .toList();
        send(new SetPlannedInventoryPayload(data.signPos(), updates));
    }

    private void send(CustomPacketPayload payload) {
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(payload));
    }
}
