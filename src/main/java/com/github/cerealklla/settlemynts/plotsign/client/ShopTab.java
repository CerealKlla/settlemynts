package com.github.cerealklla.settlemynts.plotsign.client;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.github.cerealklla.nuclyus.menu.client.NuclyusMenu;
import com.github.cerealklla.nuclyus.menu.client.NuclyusMenuScreen;
import com.github.cerealklla.settlemynts.plotsign.ClientPlotSignRequests;
import com.github.cerealklla.settlemynts.plotsign.OpenPlotConfigSignMenuPayload;
import com.github.cerealklla.settlemynts.plotsign.OpenShopPayload;
import com.github.cerealklla.settlemynts.plotsign.RequestShopPayload;
import com.github.cerealklla.settlemynts.plotsign.SetShopListingsPayload;
import com.github.cerealklla.settlemynts.plotsign.ShopAnchor;
import com.github.cerealklla.settlemynts.plotsign.ShopInventoryEntry;
import com.github.cerealklla.settlemynts.plotsign.ShopListingUpdate;
import com.github.cerealklla.settlemynts.zone.ShopResource;

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

/**
 * "Shop" tab (tabbed Plot Config Sign rework, 2026-10-23) -- present whenever this plot has a real
 * Shop (same gate the old flat Main Menu used: not Guardhouse, not Town Hall). "Open Shop" is always
 * shown (buy-mode opens a real {@code ShopCartMenu} container menu directly via vanilla's own
 * {@code player.openMenu} path server-side, so clicking it needs no special handling here -- the
 * server-pushed {@code ClientboundOpenScreenPacket} replaces whatever screen is open regardless).
 * The inline Manage-Shop table (canManage only) folds the old flat {@code client.ManageShopScreen}'s
 * scan-the-plot bulk editor in as tab content -- same scissor-clip scroll + {@link #pendingValues}
 * EditBox-persistence-across-scroll pattern that screen used (see its own former doc for the full
 * "why not read straight off the live EditBox widgets" reasoning), just positioned within {@code
 * area} instead of the full screen.
 */
public final class ShopTab implements NuclyusMenu {

    private static final int ROW_HEIGHT = 24;
    private static final int PANEL_HALF_WIDTH = 220;

    private final OpenPlotConfigSignMenuPayload data;
    private NuclyusMenuScreen host;
    private OpenShopPayload manageData;
    private final Map<ShopResource, String> pendingValues = new LinkedHashMap<>();
    private int scrollOffset;
    private Button openShopButton;
    private Button saveButton;

    public ShopTab(OpenPlotConfigSignMenuPayload data) {
        this.data = data;
    }

    @Override
    public Component title() {
        return Component.literal("Shop");
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
        manageData = null;
        pendingValues.clear();
        scrollOffset = 0;

        Rect2i area = host.contentArea();
        openShopButton = host.addMenuWidget(Button.builder(Component.literal("Open Shop"), b -> openShop())
                .bounds(area.getX() + 20, area.getY() + 16, 150, 20).build());

        if (data.canManage()) {
            send(new RequestShopPayload(new ShopAnchor.Sign(data.signPos()), true));
            saveButton = host.addMenuWidget(Button.builder(Component.literal("Save Changes"), b -> saveChanges())
                    .bounds(area.getX() + area.getWidth() / 2 - 155, footerTop(area) + 6, 150, 20).build());
        }
    }

    @Override
    public void onHidden() {
        clearRowWidgets();
        if (openShopButton != null) {
            host.removeMenuWidget(openShopButton);
            openShopButton = null;
        }
        if (saveButton != null) {
            host.removeMenuWidget(saveButton);
            saveButton = null;
        }
    }

    private int viewportTop(Rect2i area) {
        return area.getY() + 48;
    }

    private int footerTop(Rect2i area) {
        return area.getY() + area.getHeight() - 30;
    }

    private int maxScrollOffset(Rect2i area) {
        if (manageData == null) {
            return 0;
        }
        int viewport = footerTop(area) - viewportTop(area);
        return Math.max(0, manageData.inventory().size() * ROW_HEIGHT - viewport);
    }

    private final List<EditBox> rowWidgets = new java.util.ArrayList<>();

    private void clearRowWidgets() {
        for (EditBox box : rowWidgets) {
            host.removeMenuWidget(box);
        }
        rowWidgets.clear();
    }

    private void rebuildRows(Rect2i area) {
        clearRowWidgets();
        if (manageData == null) {
            return;
        }
        int centerX = area.getX() + area.getWidth() / 2;
        int viewportTop = viewportTop(area);
        int footerTop = footerTop(area);
        List<ShopInventoryEntry> inventory = manageData.inventory();
        for (int i = 0; i < inventory.size(); i++) {
            ShopInventoryEntry entry = inventory.get(i);
            int rowY = viewportTop + i * ROW_HEIGHT - scrollOffset;
            if (rowY < viewportTop || rowY + ROW_HEIGHT > footerTop) {
                continue;
            }
            EditBox priceBox = new EditBox(Minecraft.getInstance().font, centerX + 120, rowY, 90, 20, Component.literal("Sell Cost"));
            priceBox.setMaxLength(9);
            priceBox.setFilter(s -> s.isEmpty() || s.chars().allMatch(Character::isDigit));
            priceBox.setValue(pendingValues.getOrDefault(keyFor(entry), ""));
            priceBox.setResponder(value -> pendingValues.put(keyFor(entry), value));
            host.addMenuWidget(priceBox);
            rowWidgets.add(priceBox);
        }
    }

    private static ShopResource keyFor(ShopInventoryEntry entry) {
        if (entry.isTag()) {
            return ShopResource.ofTag(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, entry.resourceKey()));
        }
        return entry.customName().isPresent()
                ? ShopResource.ofExactItem(entry.resourceKey(), entry.customName().get())
                : ShopResource.ofItem(entry.resourceKey());
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, Rect2i area, int mouseX, int mouseY, float partialTick) {
        if (!data.canManage()) {
            return;
        }
        if (manageData == null) {
            ClientPlotSignRequests.takePendingShop().ifPresent(payload -> {
                manageData = payload;
                for (ShopInventoryEntry entry : manageData.inventory()) {
                    int prefill = entry.listedPrice() > 0 ? entry.listedPrice() : entry.suggestedPrice();
                    pendingValues.put(keyFor(entry), prefill > 0 ? Integer.toString(prefill) : "");
                }
                rebuildRows(area);
            });
        }

        Font font = Minecraft.getInstance().font;
        int centerX = area.getX() + area.getWidth() / 2;
        int viewportTop = viewportTop(area);
        int footerTop = footerTop(area);
        if (manageData == null) {
            String message = "Loading...";
            graphics.text(font, message, centerX - font.width(message) / 2, viewportTop, 0xFFAAAAAA);
            return;
        }

        graphics.fill(centerX - PANEL_HALF_WIDTH, viewportTop - 16, centerX + PANEL_HALF_WIDTH, footerTop + 4, 0xC0101010);
        graphics.text(font, "Item", centerX - PANEL_HALF_WIDTH + 10, viewportTop - 14, 0xFFAAAAAA);
        graphics.text(font, "Shop Stock", centerX + 10, viewportTop - 14, 0xFFAAAAAA);
        graphics.text(font, "Sell Cost", centerX + 120, viewportTop - 14, 0xFFAAAAAA);

        List<ShopInventoryEntry> inventory = manageData.inventory();
        if (inventory.isEmpty()) {
            String message = "No items found in this plot's boxes.";
            graphics.text(font, message, centerX - font.width(message) / 2, viewportTop, 0xFFAAAAAA);
            return;
        }

        graphics.enableScissor(centerX - PANEL_HALF_WIDTH, viewportTop, centerX + PANEL_HALF_WIDTH, footerTop);
        int y = viewportTop - scrollOffset;
        for (ShopInventoryEntry entry : inventory) {
            if (y + ROW_HEIGHT >= viewportTop && y <= footerTop) {
                graphics.text(font, entry.label(), centerX - PANEL_HALF_WIDTH + 10, y + 6, 0xFFFFFFFF);
                graphics.text(font, Integer.toString(entry.shopStock()), centerX + 10, y + 6, 0xFFFFFFFF);
            }
            y += ROW_HEIGHT;
        }
        graphics.disableScissor();
    }

    @Override
    public boolean mouseScrolled(Rect2i area, double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!data.canManage() || manageData == null) {
            return false;
        }
        scrollOffset = Math.max(0, Math.min(maxScrollOffset(area), scrollOffset - (int) (scrollY * ROW_HEIGHT)));
        rebuildRows(area);
        return true;
    }

    private void openShop() {
        send(new RequestShopPayload(new ShopAnchor.Sign(data.signPos()), false));
    }

    private void saveChanges() {
        if (manageData == null) {
            return;
        }
        List<ShopListingUpdate> updates = manageData.inventory().stream()
                .map(entry -> {
                    String raw = pendingValues.getOrDefault(keyFor(entry), "").trim();
                    int price = raw.isEmpty() ? 0 : Integer.parseInt(raw);
                    return new ShopListingUpdate(entry.resourceKey(), entry.isTag(), entry.customName(), price);
                })
                .toList();
        send(new SetShopListingsPayload(data.signPos(), updates));
    }

    private void send(CustomPacketPayload payload) {
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(payload));
    }
}
