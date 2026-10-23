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
import com.github.cerealklla.settlemynts.plotsign.OpenShopPayload;
import com.github.cerealklla.settlemynts.plotsign.PlannedInventoryEntry;
import com.github.cerealklla.settlemynts.plotsign.PlannedInventoryUpdate;
import com.github.cerealklla.settlemynts.plotsign.RequestPlannedInventoryPayload;
import com.github.cerealklla.settlemynts.plotsign.RequestShopPayload;
import com.github.cerealklla.settlemynts.plotsign.SetPlannedInventoryPayload;
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
import net.minecraft.resources.Identifier;

/**
 * "Shop" tab (tabbed Plot Config Sign rework, 2026-10-23; sub-tabbed 2026-10-24 per explicit user
 * feedback: "I think this is supposed to be the shop config page? but there should be a separate
 * button for that... a second set of sub-tabs within the shop page for Shop, Manage Shop, Preferred
 * [Planned] Inventory, the second two only visible to the Owner or Mayor/Town Planner") -- present
 * whenever this plot has a real Shop (not Guardhouse, not Town Hall). Its own secondary tab strip
 * (below the main K-menu-style tab bar, same selection-greys-out-the-active-choice convention as
 * {@link ConfigureTab}'s Ore/Stone buttons) switches between three pages:
 * <ul>
 *   <li><b>Shop</b> (always visible) -- just "Open Shop," which opens the real buy-mode {@code
 *   ShopCartMenu} container menu directly (server-pushed, no client dispatch needed here).</li>
 *   <li><b>Manage Shop</b> ({@code canManage} only) -- the scan-the-plot price editor, folded in
 *   from the old pop-up {@code client.ManageShopScreen}.</li>
 *   <li><b>Planned Inventory</b> ({@code canManage} only) -- folded in from the old separate
 *   top-level tab/pop-up {@code client.PlannedInventoryScreen}, since it's really a Shop-adjacent
 *   owner setting, not its own top-level concern.</li>
 * </ul>
 * Both editor pages share the same scissor-clip/EditBox/pending-values-map pattern (row widgets only
 * exist while scrolled into view -- see the original {@code ManageShopScreen}/{@code
 * PlannedInventoryScreen} docs for the full "why not read straight off the live EditBox" reasoning),
 * duplicated rather than shared per this suite's own established "copy and tweak" convention for a
 * pair this small.
 */
public final class ShopTab implements NuclyusMenu {

    private enum SubTab { SHOP, MANAGE, PLANNED }

    private static final int ROW_HEIGHT = 24;
    private static final int PANEL_HALF_WIDTH = 220;
    private static final int SUB_TAB_Y_OFFSET = 16;
    private static final int CONTENT_TOP_OFFSET = 56;

    private final OpenPlotConfigSignMenuPayload data;
    private NuclyusMenuScreen host;
    private SubTab subTab = SubTab.SHOP;

    private Button shopSubTabButton;
    private Button manageSubTabButton;
    private Button plannedSubTabButton;

    // "Shop" page.
    private Button openShopButton;

    // "Manage Shop" page.
    private OpenShopPayload manageData;
    private final Map<ShopResource, String> managePendingValues = new LinkedHashMap<>();
    private final List<EditBox> manageRowWidgets = new ArrayList<>();
    private int manageScrollOffset;
    private Button manageSaveButton;

    // "Planned Inventory" page.
    private OpenPlannedInventoryPayload plannedData;
    private final Map<Identifier, String> plannedPendingValues = new LinkedHashMap<>();
    private final List<EditBox> plannedRowWidgets = new ArrayList<>();
    private int plannedScrollOffset;
    private Button plannedSaveButton;

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
        subTab = SubTab.SHOP;
        manageData = null;
        plannedData = null;
        managePendingValues.clear();
        plannedPendingValues.clear();
        manageScrollOffset = 0;
        plannedScrollOffset = 0;

        Rect2i area = host.contentArea();
        addSubTabButtons(area);
        addPageWidgets(area);
    }

    @Override
    public void onHidden() {
        removeSubTabButtons();
        removePageWidgets();
    }

    private void addSubTabButtons(Rect2i area) {
        int x = area.getX() + 20;
        int y = area.getY() + SUB_TAB_Y_OFFSET;
        shopSubTabButton = host.addMenuWidget(Button.builder(Component.literal("Shop"), b -> switchSubTab(SubTab.SHOP))
                .bounds(x, y, 100, 20).build());
        x += 105;
        if (data.canManage()) {
            manageSubTabButton = host.addMenuWidget(Button.builder(Component.literal("Manage Shop"), b -> switchSubTab(SubTab.MANAGE))
                    .bounds(x, y, 120, 20).build());
            x += 125;
            plannedSubTabButton = host.addMenuWidget(Button.builder(Component.literal("Planned Inventory"), b -> switchSubTab(SubTab.PLANNED))
                    .bounds(x, y, 150, 20).build());
        }
        refreshSubTabButtons();
    }

    private void removeSubTabButtons() {
        if (shopSubTabButton != null) {
            host.removeMenuWidget(shopSubTabButton);
            shopSubTabButton = null;
        }
        if (manageSubTabButton != null) {
            host.removeMenuWidget(manageSubTabButton);
            manageSubTabButton = null;
        }
        if (plannedSubTabButton != null) {
            host.removeMenuWidget(plannedSubTabButton);
            plannedSubTabButton = null;
        }
    }

    private void refreshSubTabButtons() {
        if (shopSubTabButton != null) {
            shopSubTabButton.active = subTab != SubTab.SHOP;
        }
        if (manageSubTabButton != null) {
            manageSubTabButton.active = subTab != SubTab.MANAGE;
        }
        if (plannedSubTabButton != null) {
            plannedSubTabButton.active = subTab != SubTab.PLANNED;
        }
    }

    private void switchSubTab(SubTab newSubTab) {
        if (newSubTab == subTab) {
            return;
        }
        removePageWidgets();
        subTab = newSubTab;
        refreshSubTabButtons();
        addPageWidgets(host.contentArea());
    }

    private void addPageWidgets(Rect2i area) {
        switch (subTab) {
            case SHOP -> openShopButton = host.addMenuWidget(Button.builder(Component.literal("Open Shop"), b -> openShop())
                    .bounds(area.getX() + 20, area.getY() + CONTENT_TOP_OFFSET, 150, 20).build());
            case MANAGE -> {
                manageData = null;
                managePendingValues.clear();
                manageScrollOffset = 0;
                send(new RequestShopPayload(new ShopAnchor.Sign(data.signPos()), true));
                manageSaveButton = host.addMenuWidget(Button.builder(Component.literal("Save Changes"), b -> saveManageChanges())
                        .bounds(area.getX() + area.getWidth() / 2 - 75, manageFooterTop(area) + 6, 150, 20).build());
            }
            case PLANNED -> {
                plannedData = null;
                plannedPendingValues.clear();
                plannedScrollOffset = 0;
                send(new RequestPlannedInventoryPayload(data.signPos()));
                plannedSaveButton = host.addMenuWidget(Button.builder(Component.literal("Save Changes"), b -> savePlannedChanges())
                        .bounds(area.getX() + area.getWidth() / 2 - 75, plannedFooterTop(area) + 6, 150, 20).build());
            }
        }
    }

    private void removePageWidgets() {
        if (openShopButton != null) {
            host.removeMenuWidget(openShopButton);
            openShopButton = null;
        }
        clearManageRowWidgets();
        if (manageSaveButton != null) {
            host.removeMenuWidget(manageSaveButton);
            manageSaveButton = null;
        }
        clearPlannedRowWidgets();
        if (plannedSaveButton != null) {
            host.removeMenuWidget(plannedSaveButton);
            plannedSaveButton = null;
        }
    }

    // --- Manage Shop page ---

    private int manageViewportTop(Rect2i area) {
        return area.getY() + CONTENT_TOP_OFFSET;
    }

    private int manageFooterTop(Rect2i area) {
        return area.getY() + area.getHeight() - 30;
    }

    private int manageMaxScrollOffset(Rect2i area) {
        if (manageData == null) {
            return 0;
        }
        int viewport = manageFooterTop(area) - manageViewportTop(area);
        return Math.max(0, manageData.inventory().size() * ROW_HEIGHT - viewport);
    }

    private void clearManageRowWidgets() {
        for (EditBox box : manageRowWidgets) {
            host.removeMenuWidget(box);
        }
        manageRowWidgets.clear();
    }

    private void rebuildManageRows(Rect2i area) {
        clearManageRowWidgets();
        if (manageData == null) {
            return;
        }
        int centerX = area.getX() + area.getWidth() / 2;
        int viewportTop = manageViewportTop(area);
        int footerTop = manageFooterTop(area);
        List<ShopInventoryEntry> inventory = manageData.inventory();
        for (int i = 0; i < inventory.size(); i++) {
            ShopInventoryEntry entry = inventory.get(i);
            int rowY = viewportTop + i * ROW_HEIGHT - manageScrollOffset;
            if (rowY < viewportTop || rowY + ROW_HEIGHT > footerTop) {
                continue;
            }
            EditBox priceBox = new EditBox(Minecraft.getInstance().font, centerX + 120, rowY, 90, 20, Component.literal("Sell Cost"));
            priceBox.setMaxLength(9);
            priceBox.setFilter(s -> s.isEmpty() || s.chars().allMatch(Character::isDigit));
            priceBox.setValue(managePendingValues.getOrDefault(keyFor(entry), ""));
            priceBox.setResponder(value -> managePendingValues.put(keyFor(entry), value));
            host.addMenuWidget(priceBox);
            manageRowWidgets.add(priceBox);
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

    private void renderManagePage(GuiGraphicsExtractor graphics, Rect2i area) {
        if (manageData == null) {
            ClientPlotSignRequests.takePendingShop().ifPresent(payload -> {
                manageData = payload;
                for (ShopInventoryEntry entry : manageData.inventory()) {
                    int prefill = entry.listedPrice() > 0 ? entry.listedPrice() : entry.suggestedPrice();
                    managePendingValues.put(keyFor(entry), prefill > 0 ? Integer.toString(prefill) : "");
                }
                rebuildManageRows(area);
            });
        }

        Font font = Minecraft.getInstance().font;
        int centerX = area.getX() + area.getWidth() / 2;
        int viewportTop = manageViewportTop(area);
        int footerTop = manageFooterTop(area);
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
        int y = viewportTop - manageScrollOffset;
        for (ShopInventoryEntry entry : inventory) {
            if (y + ROW_HEIGHT >= viewportTop && y <= footerTop) {
                graphics.text(font, entry.label(), centerX - PANEL_HALF_WIDTH + 10, y + 6, 0xFFFFFFFF);
                graphics.text(font, Integer.toString(entry.shopStock()), centerX + 10, y + 6, 0xFFFFFFFF);
            }
            y += ROW_HEIGHT;
        }
        graphics.disableScissor();
    }

    private void saveManageChanges() {
        if (manageData == null) {
            return;
        }
        List<ShopListingUpdate> updates = manageData.inventory().stream()
                .map(entry -> {
                    String raw = managePendingValues.getOrDefault(keyFor(entry), "").trim();
                    int price = raw.isEmpty() ? 0 : Integer.parseInt(raw);
                    return new ShopListingUpdate(entry.resourceKey(), entry.isTag(), entry.customName(), price);
                })
                .toList();
        send(new SetShopListingsPayload(data.signPos(), updates));
    }

    // --- Planned Inventory page ---

    private int plannedViewportTop(Rect2i area) {
        return area.getY() + CONTENT_TOP_OFFSET;
    }

    private int plannedFooterTop(Rect2i area) {
        return area.getY() + area.getHeight() - 30;
    }

    private int plannedMaxScrollOffset(Rect2i area) {
        if (plannedData == null) {
            return 0;
        }
        int viewport = plannedFooterTop(area) - plannedViewportTop(area);
        return Math.max(0, plannedData.entries().size() * ROW_HEIGHT - viewport);
    }

    private void clearPlannedRowWidgets() {
        for (EditBox box : plannedRowWidgets) {
            host.removeMenuWidget(box);
        }
        plannedRowWidgets.clear();
    }

    private void rebuildPlannedRows(Rect2i area) {
        clearPlannedRowWidgets();
        if (plannedData == null) {
            return;
        }
        int centerX = area.getX() + area.getWidth() / 2;
        int viewportTop = plannedViewportTop(area);
        int footerTop = plannedFooterTop(area);
        List<PlannedInventoryEntry> entries = plannedData.entries();
        for (int i = 0; i < entries.size(); i++) {
            PlannedInventoryEntry entry = entries.get(i);
            int rowY = viewportTop + i * ROW_HEIGHT - plannedScrollOffset;
            if (rowY < viewportTop || rowY + ROW_HEIGHT > footerTop) {
                continue;
            }
            EditBox targetBox = new EditBox(Minecraft.getInstance().font, centerX + 120, rowY, 90, 20, Component.literal("Target Qty"));
            targetBox.setMaxLength(9);
            targetBox.setFilter(s -> s.isEmpty() || s.chars().allMatch(Character::isDigit));
            targetBox.setValue(plannedPendingValues.getOrDefault(entry.resourceKey(), ""));
            targetBox.setResponder(value -> plannedPendingValues.put(entry.resourceKey(), value));
            host.addMenuWidget(targetBox);
            plannedRowWidgets.add(targetBox);
        }
    }

    private void renderPlannedPage(GuiGraphicsExtractor graphics, Rect2i area) {
        if (plannedData == null) {
            ClientPlotSignRequests.takePendingPlannedInventory().ifPresent(payload -> {
                plannedData = payload;
                for (PlannedInventoryEntry entry : plannedData.entries()) {
                    plannedPendingValues.put(entry.resourceKey(), entry.targetCount() >= 0 ? Integer.toString(entry.targetCount()) : "");
                }
                rebuildPlannedRows(area);
            });
        }

        Font font = Minecraft.getInstance().font;
        int centerX = area.getX() + area.getWidth() / 2;
        int viewportTop = plannedViewportTop(area);
        int footerTop = plannedFooterTop(area);
        if (plannedData == null) {
            String message = "Loading...";
            graphics.text(font, message, centerX - font.width(message) / 2, viewportTop, 0xFFAAAAAA);
            return;
        }

        graphics.fill(centerX - PANEL_HALF_WIDTH, viewportTop - 16, centerX + PANEL_HALF_WIDTH, footerTop + 4, 0xC0101010);
        graphics.text(font, "Item", centerX - PANEL_HALF_WIDTH + 10, viewportTop - 14, 0xFFAAAAAA);
        graphics.text(font, "Current Stock", centerX + 10, viewportTop - 14, 0xFFAAAAAA);
        graphics.text(font, "Target Qty", centerX + 120, viewportTop - 14, 0xFFAAAAAA);

        List<PlannedInventoryEntry> entries = plannedData.entries();
        if (entries.isEmpty()) {
            String message = "No items found in this plot's boxes.";
            graphics.text(font, message, centerX - font.width(message) / 2, viewportTop, 0xFFAAAAAA);
            return;
        }

        graphics.enableScissor(centerX - PANEL_HALF_WIDTH, viewportTop, centerX + PANEL_HALF_WIDTH, footerTop);
        int y = viewportTop - plannedScrollOffset;
        for (PlannedInventoryEntry entry : entries) {
            if (y + ROW_HEIGHT >= viewportTop && y <= footerTop) {
                graphics.text(font, entry.label(), centerX - PANEL_HALF_WIDTH + 10, y + 6, 0xFFFFFFFF);
                graphics.text(font, Integer.toString(entry.currentStock()), centerX + 10, y + 6, 0xFFFFFFFF);
            }
            y += ROW_HEIGHT;
        }
        graphics.disableScissor();
    }

    private void savePlannedChanges() {
        if (plannedData == null) {
            return;
        }
        List<PlannedInventoryUpdate> updates = plannedData.entries().stream()
                .map(entry -> {
                    String raw = plannedPendingValues.getOrDefault(entry.resourceKey(), "").trim();
                    boolean managed = !raw.isEmpty();
                    int target = managed ? Integer.parseInt(raw) : 0;
                    return new PlannedInventoryUpdate(entry.resourceKey(), entry.isTag(), managed, target);
                })
                .toList();
        send(new SetPlannedInventoryPayload(data.signPos(), updates));
    }

    // --- NuclyusMenu plumbing ---

    @Override
    public void render(GuiGraphicsExtractor graphics, Rect2i area, int mouseX, int mouseY, float partialTick) {
        switch (subTab) {
            case SHOP -> {
                // Nothing to render beyond the "Open Shop" button itself.
            }
            case MANAGE -> renderManagePage(graphics, area);
            case PLANNED -> renderPlannedPage(graphics, area);
        }
    }

    @Override
    public boolean mouseScrolled(Rect2i area, double mouseX, double mouseY, double scrollX, double scrollY) {
        if (subTab == SubTab.MANAGE && manageData != null) {
            manageScrollOffset = Math.max(0, Math.min(manageMaxScrollOffset(area), manageScrollOffset - (int) (scrollY * ROW_HEIGHT)));
            rebuildManageRows(area);
            return true;
        }
        if (subTab == SubTab.PLANNED && plannedData != null) {
            plannedScrollOffset = Math.max(0, Math.min(plannedMaxScrollOffset(area), plannedScrollOffset - (int) (scrollY * ROW_HEIGHT)));
            rebuildPlannedRows(area);
            return true;
        }
        return false;
    }

    private void openShop() {
        send(new RequestShopPayload(new ShopAnchor.Sign(data.signPos()), false));
    }

    private void send(CustomPacketPayload payload) {
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(payload));
    }
}
