package com.github.cerealklla.settlemynts.plotsign.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.github.cerealklla.nuclyus.menu.client.NuclyusContainerScreen;
import com.github.cerealklla.nuclyus.menu.client.NuclyusMenu;
import com.github.cerealklla.nuclyus.menu.client.WidgetHost;
import com.github.cerealklla.settlemynts.plotsign.ClientPlotSignRequests;
import com.github.cerealklla.settlemynts.plotsign.OpenPlannedInventoryPayload;
import com.github.cerealklla.settlemynts.plotsign.OpenPlotConfigSignMenuPayload;
import com.github.cerealklla.settlemynts.plotsign.OpenShopPayload;
import com.github.cerealklla.settlemynts.plotsign.PlannedInventoryEntry;
import com.github.cerealklla.settlemynts.plotsign.PlannedInventoryUpdate;
import com.github.cerealklla.settlemynts.plotsign.PlotConfigMenu;
import com.github.cerealklla.settlemynts.plotsign.RequestPlannedInventoryPayload;
import com.github.cerealklla.settlemynts.plotsign.RequestShopPayload;
import com.github.cerealklla.settlemynts.plotsign.SetPlannedInventoryPayload;
import com.github.cerealklla.settlemynts.plotsign.SetShopListingsPayload;
import com.github.cerealklla.settlemynts.plotsign.ShopAnchor;
import com.github.cerealklla.settlemynts.plotsign.ShopInventoryEntry;
import com.github.cerealklla.settlemynts.plotsign.ShopListingEntry;
import com.github.cerealklla.settlemynts.plotsign.ShopListingUpdate;
import com.github.cerealklla.settlemynts.zone.ShopResource;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
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
 * is a genuine second tier of tabs (rebuilt 2026-10-24 per a repeated, explicit user correction --
 * "I have asked a few times now for this to be two tiers of tabs... keep using buttons up top" --
 * the original version used plain floating {@link Button} widgets instead, which both looked
 * nothing like {@link NuclyusContainerScreen}'s own real tab bar and left the real slot panel's
 * fixed position overlapping them, since nothing had ever pushed {@code topPos} down to clear a
 * second band). {@link #renderSubTabBar} mirrors {@code NuclyusContainerScreen}'s own tab-bar
 * rendering technique exactly (full-width background band, text-width-sized segments, the last one
 * absorbing slack, active/inactive shading) and sits directly beneath it; {@link #mouseClicked}
 * hit-tests the same rects manually instead of real widgets, same reason {@code
 * NuclyusContainerScreen} itself doesn't use {@code Button} for its own top-level tabs. {@code
 * client.PlotConfigScreen#init} pushes {@code topPos} down by {@link #SUB_TAB_BAR_HEIGHT} whenever
 * this band can appear, so the real Shop slot panel (which this class doesn't own the layout of)
 * always clears it regardless of which sub-tab is currently active. Switches between three pages:
 * <ul>
 *   <li><b>Shop</b> (always visible) -- the real Shop/Buy/Sell/Player slots {@link PlotConfigMenu}
 *   already carries, merged in directly 2026-10-24 (explicit request: "Place it fully within the
 *   unified menu, not as a separate area") -- used to open a separate {@code ShopCartMenu} window;
 *   now this sub-tab just toggles {@link PlotConfigMenu#setShopSlotsActive} and draws the same
 *   column headers/price text/gold totals/Confirm-Purchase/Auto-Sell/scroll controls the old
 *   {@code ShopCartScreen} drew, at the real slot panel's own position ({@link
 *   NuclyusContainerScreen#slotPanelLeft}/{@link NuclyusContainerScreen#slotPanelTop}).</li>
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

    private static final SubTab[] SUB_TAB_ORDER = { SubTab.SHOP, SubTab.MANAGE, SubTab.PLANNED };

    private static final int ROW_HEIGHT = 24;
    private static final int PANEL_HALF_WIDTH = 220;

    /** Real second tier of tabs -- see this class's own doc. Same height as {@link NuclyusContainerScreen#TAB_BAR_HEIGHT}. */
    public static final int SUB_TAB_BAR_HEIGHT = 24;
    private static final int SUB_TAB_PADDING = 6;
    // Same palette shape as NuclyusContainerScreen's own tab bar, one shade darker background so the
    // two tiers read as stacked rather than identical -- duplicated rather than shared, same
    // established "copy and tweak" convention NuclyusContainerScreen's own doc cites for its copy of
    // NuclyusMenuScreen's palette.
    private static final int SUB_TAB_BAR_BG = 0xFF2A2A2A;
    private static final int SUB_TAB_ACTIVE_BG = 0xFFC6C6C6;
    private static final int SUB_TAB_INACTIVE_BG = 0xFF8B8B8B;
    private static final int SUB_TAB_TEXT_COLOR = 0xFF202020;

    private static final int CONTENT_TOP_OFFSET = SUB_TAB_BAR_HEIGHT + 24;

    // Real slot panel's own layout (must stay in lockstep with PlotConfigMenu's identical copies).
    private static final int SHOP_TOP_Y = 18;
    private static final int SHOP_ROW_HEIGHT = 18;
    private static final int SHOP_X = 8;
    private static final int BUY_X = 230;
    private static final int SELL_X = 260;
    private static final int PLAYER_INV_X = 300;
    private static final int SCROLLBAR_X = BUY_X - 16;

    private final OpenPlotConfigSignMenuPayload data;
    private final PlotConfigMenu menu;
    private WidgetHost host;
    private NuclyusContainerScreen<?> containerScreen;
    private SubTab subTab = SubTab.SHOP;

    // "Shop" page.
    private Button confirmButton;
    private Button autoSellButton;
    private Button scrollUpButton;
    private Button scrollDownButton;

    // "Manage Shop" page.
    private OpenShopPayload manageData;
    private final Map<ShopResource, String> managePendingValues = new LinkedHashMap<>();
    private final List<EditBox> manageRowWidgets = new ArrayList<>();
    private int manageScrollOffset;
    private Button manageSaveButton;

    // "Planned Inventory" page.
    private static final int PLANNED_PANEL_HALF_WIDTH = 260;
    private OpenPlannedInventoryPayload plannedData;
    private final Map<Identifier, String> plannedForSalePendingValues = new LinkedHashMap<>();
    private final Map<Identifier, String> plannedKeepPrivatePendingValues = new LinkedHashMap<>();
    private final List<EditBox> plannedRowWidgets = new ArrayList<>();
    private int plannedScrollOffset;
    private Button plannedSaveButton;

    public ShopTab(OpenPlotConfigSignMenuPayload data, PlotConfigMenu menu) {
        this.data = data;
        this.menu = menu;
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
        this.host = (WidgetHost) host;
        this.containerScreen = (NuclyusContainerScreen<?>) host;
    }

    @Override
    public void onShown() {
        subTab = SubTab.SHOP;
        manageData = null;
        plannedData = null;
        managePendingValues.clear();
        plannedForSalePendingValues.clear();
        plannedKeepPrivatePendingValues.clear();
        manageScrollOffset = 0;
        plannedScrollOffset = 0;
        menu.setShopSlotsActive(true);

        addPageWidgets(host.contentArea());
    }

    @Override
    public void onHidden() {
        menu.setShopSlotsActive(false);
        removePageWidgets();
    }

    /** Only "Shop" exists at all for a non-manager viewer -- a one-entry band would be pointless chrome, so the whole second tier is skipped (see {@link #render}/{@link #mouseClicked}). */
    private List<SubTab> visibleSubTabs() {
        return data.canManage() ? List.of(SUB_TAB_ORDER) : List.of(SubTab.SHOP);
    }

    private static String subTabLabel(SubTab tab) {
        return switch (tab) {
            case SHOP -> "Shop";
            case MANAGE -> "Manage Shop";
            case PLANNED -> "Planned Inventory";
        };
    }

    /** Same text-width-sized-segments-plus-slack-absorbing-last-tab algorithm as {@code NuclyusContainerScreen#tabRects()}. */
    private Rect2i[] subTabRects(Rect2i area) {
        List<SubTab> visible = visibleSubTabs();
        Font font = Minecraft.getInstance().font;
        int[] widths = new int[visible.size()];
        int sum = 0;
        for (int i = 0; i < visible.size(); i++) {
            widths[i] = font.width(subTabLabel(visible.get(i))) + SUB_TAB_PADDING * 2;
            sum += widths[i];
        }
        int lastIndex = widths.length - 1;
        widths[lastIndex] = Math.max(widths[lastIndex], area.getWidth() - (sum - widths[lastIndex]));
        Rect2i[] rects = new Rect2i[visible.size()];
        int x = area.getX();
        for (int i = 0; i < widths.length; i++) {
            rects[i] = new Rect2i(x, area.getY(), widths[i], SUB_TAB_BAR_HEIGHT);
            x += widths[i];
        }
        return rects;
    }

    private void renderSubTabBar(GuiGraphicsExtractor graphics, Rect2i area) {
        Font font = Minecraft.getInstance().font;
        graphics.fill(area.getX(), area.getY(), area.getX() + area.getWidth(), area.getY() + SUB_TAB_BAR_HEIGHT, SUB_TAB_BAR_BG);
        List<SubTab> visible = visibleSubTabs();
        Rect2i[] rects = subTabRects(area);
        for (int i = 0; i < rects.length; i++) {
            Rect2i rect = rects[i];
            boolean active = subTab == visible.get(i);
            int bg = active ? SUB_TAB_ACTIVE_BG : SUB_TAB_INACTIVE_BG;
            graphics.fill(rect.getX(), rect.getY(), rect.getX() + rect.getWidth(), rect.getY() + rect.getHeight(), bg);
            String label = subTabLabel(visible.get(i));
            graphics.text(font, label,
                    rect.getX() + (rect.getWidth() - font.width(label)) / 2,
                    rect.getY() + (SUB_TAB_BAR_HEIGHT - font.lineHeight) / 2,
                    SUB_TAB_TEXT_COLOR, false);
        }
    }

    private void switchSubTab(SubTab newSubTab) {
        if (newSubTab == subTab) {
            return;
        }
        removePageWidgets();
        subTab = newSubTab;
        menu.setShopSlotsActive(subTab == SubTab.SHOP);
        addPageWidgets(host.contentArea());
    }

    private void addPageWidgets(Rect2i area) {
        switch (subTab) {
            case SHOP -> {
                int left = containerScreen.slotPanelLeft();
                int top = containerScreen.slotPanelTop();
                int confirmY = top + SHOP_TOP_Y + PlotConfigMenu.VISIBLE_SHOP_ROWS * SHOP_ROW_HEIGHT + 12;
                confirmButton = host.addMenuWidget(Button.builder(Component.literal("Confirm Purchase"),
                                b -> Minecraft.getInstance().gameMode.handleInventoryButtonClick(menu.containerId, PlotConfigMenu.CONFIRM_BUTTON_ID))
                        .bounds(left + SHOP_X, confirmY, 150, 20).build());
                autoSellButton = host.addMenuWidget(Button.builder(Component.literal("Auto Sell"),
                                b -> Minecraft.getInstance().gameMode.handleInventoryButtonClick(menu.containerId, PlotConfigMenu.AUTO_SELL_BUTTON_ID))
                        .bounds(left + SHOP_X + 160, confirmY, 100, 20).build());
                scrollUpButton = host.addMenuWidget(Button.builder(Component.literal("^"),
                                b -> Minecraft.getInstance().gameMode.handleInventoryButtonClick(menu.containerId, PlotConfigMenu.SCROLL_UP_BUTTON_ID))
                        .bounds(left + SCROLLBAR_X, top + SHOP_TOP_Y, 12, 12).build());
                scrollDownButton = host.addMenuWidget(Button.builder(Component.literal("v"),
                                b -> Minecraft.getInstance().gameMode.handleInventoryButtonClick(menu.containerId, PlotConfigMenu.SCROLL_DOWN_BUTTON_ID))
                        .bounds(left + SCROLLBAR_X, top + SHOP_TOP_Y + PlotConfigMenu.VISIBLE_SHOP_ROWS * SHOP_ROW_HEIGHT - 12, 12, 12).build());
            }
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
                plannedForSalePendingValues.clear();
                plannedKeepPrivatePendingValues.clear();
                plannedScrollOffset = 0;
                send(new RequestPlannedInventoryPayload(data.signPos()));
                plannedSaveButton = host.addMenuWidget(Button.builder(Component.literal("Save Changes"), b -> savePlannedChanges())
                        .bounds(area.getX() + area.getWidth() / 2 - 75, plannedFooterTop(area) + 6, 150, 20).build());
            }
        }
    }

    private void removePageWidgets() {
        if (confirmButton != null) {
            host.removeMenuWidget(confirmButton);
            confirmButton = null;
        }
        if (autoSellButton != null) {
            host.removeMenuWidget(autoSellButton);
            autoSellButton = null;
        }
        if (scrollUpButton != null) {
            host.removeMenuWidget(scrollUpButton);
            scrollUpButton = null;
        }
        if (scrollDownButton != null) {
            host.removeMenuWidget(scrollDownButton);
            scrollDownButton = null;
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

    /**
     * Sized to actual row content, clamped to the window's own available space -- real report,
     * 2026-10-24: "the GUI is also a little strangely stretched." Used to unconditionally stretch all
     * the way to {@code area.getHeight() - 30} regardless of row count, since {@code area} is {@code
     * host.contentArea()}'s full window span, not this panel's own natural size (see {@code
     * client.PlotConfigScreen}'s own class doc on why {@code imageWidth}/{@code imageHeight} don't
     * constrain this tab at all) -- a shop with only a handful of listings left a large empty gap
     * between the last row and the Save Changes button. A maximally-tall window fallback still applies
     * while {@code manageData} hasn't arrived yet (nothing to size against) and as a hard ceiling once
     * it has (so a long list still scrolls instead of overflowing the window).
     */
    private int manageFooterTop(Rect2i area) {
        int maxFooter = area.getY() + area.getHeight() - 30;
        if (manageData == null) {
            return maxFooter;
        }
        int rowCount = Math.max(1, manageData.inventory().size());
        return Math.min(maxFooter, manageViewportTop(area) + rowCount * ROW_HEIGHT);
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

    /** Same content-sized-footer fix as {@link #manageFooterTop}'s own doc -- see that method. */
    private int plannedFooterTop(Rect2i area) {
        int maxFooter = area.getY() + area.getHeight() - 30;
        if (plannedData == null) {
            return maxFooter;
        }
        int rowCount = Math.max(1, plannedData.entries().size());
        return Math.min(maxFooter, plannedViewportTop(area) + rowCount * ROW_HEIGHT);
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

    // Column x-positions for the Planned Inventory page's four columns (Item | In Stock | For Sale |
    // Keep Private) -- a dedicated, wider half-width than Manage Shop's own PANEL_HALF_WIDTH, since
    // this page needs room for two numeric EditBoxes instead of one.
    private static final int PLANNED_STOCK_X_OFFSET = -60;
    private static final int PLANNED_FOR_SALE_X_OFFSET = 10;
    private static final int PLANNED_FOR_SALE_WIDTH = 70;
    private static final int PLANNED_KEEP_PRIVATE_X_OFFSET = 100;
    private static final int PLANNED_KEEP_PRIVATE_WIDTH = 90;

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
            EditBox forSaleBox = new EditBox(Minecraft.getInstance().font, centerX + PLANNED_FOR_SALE_X_OFFSET, rowY, PLANNED_FOR_SALE_WIDTH, 20, Component.literal("For Sale"));
            forSaleBox.setMaxLength(9);
            forSaleBox.setFilter(s -> s.isEmpty() || s.chars().allMatch(Character::isDigit));
            forSaleBox.setValue(plannedForSalePendingValues.getOrDefault(entry.resourceKey(), ""));
            forSaleBox.setResponder(value -> plannedForSalePendingValues.put(entry.resourceKey(), value));
            host.addMenuWidget(forSaleBox);
            plannedRowWidgets.add(forSaleBox);

            EditBox keepPrivateBox = new EditBox(Minecraft.getInstance().font, centerX + PLANNED_KEEP_PRIVATE_X_OFFSET, rowY, PLANNED_KEEP_PRIVATE_WIDTH, 20, Component.literal("Keep Private"));
            keepPrivateBox.setMaxLength(9);
            keepPrivateBox.setFilter(s -> s.isEmpty() || s.chars().allMatch(Character::isDigit));
            keepPrivateBox.setValue(plannedKeepPrivatePendingValues.getOrDefault(entry.resourceKey(), ""));
            keepPrivateBox.setResponder(value -> plannedKeepPrivatePendingValues.put(entry.resourceKey(), value));
            host.addMenuWidget(keepPrivateBox);
            plannedRowWidgets.add(keepPrivateBox);
        }
    }

    private void renderPlannedPage(GuiGraphicsExtractor graphics, Rect2i area) {
        if (plannedData == null) {
            ClientPlotSignRequests.takePendingPlannedInventory().ifPresent(payload -> {
                plannedData = payload;
                for (PlannedInventoryEntry entry : plannedData.entries()) {
                    plannedForSalePendingValues.put(entry.resourceKey(), entry.targetCount() >= 0 ? Integer.toString(entry.targetCount()) : "");
                    plannedKeepPrivatePendingValues.put(entry.resourceKey(), entry.keepPrivateCount() > 0 ? Integer.toString(entry.keepPrivateCount()) : "");
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

        graphics.fill(centerX - PLANNED_PANEL_HALF_WIDTH, viewportTop - 16, centerX + PLANNED_PANEL_HALF_WIDTH, footerTop + 4, 0xC0101010);
        graphics.text(font, "Item", centerX - PLANNED_PANEL_HALF_WIDTH + 10, viewportTop - 14, 0xFFAAAAAA);
        graphics.text(font, "In Stock", centerX + PLANNED_STOCK_X_OFFSET, viewportTop - 14, 0xFFAAAAAA);
        graphics.text(font, "For Sale", centerX + PLANNED_FOR_SALE_X_OFFSET, viewportTop - 14, 0xFFAAAAAA);
        graphics.text(font, "Keep Private", centerX + PLANNED_KEEP_PRIVATE_X_OFFSET, viewportTop - 14, 0xFFAAAAAA);

        List<PlannedInventoryEntry> entries = plannedData.entries();
        if (entries.isEmpty()) {
            String message = "No items found in this plot's boxes.";
            graphics.text(font, message, centerX - font.width(message) / 2, viewportTop, 0xFFAAAAAA);
            return;
        }

        graphics.enableScissor(centerX - PLANNED_PANEL_HALF_WIDTH, viewportTop, centerX + PLANNED_PANEL_HALF_WIDTH, footerTop);
        int y = viewportTop - plannedScrollOffset;
        for (PlannedInventoryEntry entry : entries) {
            if (y + ROW_HEIGHT >= viewportTop && y <= footerTop) {
                graphics.text(font, entry.label(), centerX - PLANNED_PANEL_HALF_WIDTH + 10, y + 6, 0xFFFFFFFF);
                graphics.text(font, Integer.toString(entry.currentStock()), centerX + PLANNED_STOCK_X_OFFSET, y + 6, 0xFFFFFFFF);
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
                    String rawForSale = plannedForSalePendingValues.getOrDefault(entry.resourceKey(), "").trim();
                    String rawKeepPrivate = plannedKeepPrivatePendingValues.getOrDefault(entry.resourceKey(), "").trim();
                    boolean managed = !rawForSale.isEmpty() || !rawKeepPrivate.isEmpty();
                    int forSale = rawForSale.isEmpty() ? 0 : Integer.parseInt(rawForSale);
                    int keepPrivate = rawKeepPrivate.isEmpty() ? 0 : Integer.parseInt(rawKeepPrivate);
                    return new PlannedInventoryUpdate(entry.resourceKey(), entry.isTag(), managed, forSale, keepPrivate);
                })
                .toList();
        send(new SetPlannedInventoryPayload(data.signPos(), updates));
    }

    // --- NuclyusMenu plumbing ---

    @Override
    public void render(GuiGraphicsExtractor graphics, Rect2i area, int mouseX, int mouseY, float partialTick) {
        switch (subTab) {
            case SHOP -> renderShopPage(graphics);
            case MANAGE -> renderManagePage(graphics, area);
            case PLANNED -> renderPlannedPage(graphics, area);
        }
        if (data.canManage()) {
            renderSubTabBar(graphics, area);
        }
    }

    @Override
    public boolean mouseClicked(Rect2i area, MouseButtonEvent event, boolean doubleClick) {
        if (!data.canManage() || event.y() < area.getY() || event.y() >= area.getY() + SUB_TAB_BAR_HEIGHT) {
            return false;
        }
        List<SubTab> visible = visibleSubTabs();
        Rect2i[] rects = subTabRects(area);
        for (int i = 0; i < rects.length; i++) {
            if (rects[i].contains((int) event.x(), (int) event.y())) {
                switchSubTab(visible.get(i));
                return true;
            }
        }
        return true; // Swallow the click regardless -- the band tiles the full width, so landing here with no rect hit shouldn't happen, but a click inside this strip should never fall through to a real slot beneath it either way.
    }

    /**
     * Column headers, per-row price/stock text, gold totals, scroll label, and the hint line -- the
     * exact same decorations the old (now-deleted-from-this-flow) {@code ShopCartScreen} used to draw
     * around its own real slots, just now positioned off {@link NuclyusContainerScreen#slotPanelLeft}/
     * {@link NuclyusContainerScreen#slotPanelTop} instead of its own {@code leftPos}/{@code topPos}
     * (this tab doesn't own the slot panel's layout, {@link PlotConfigMenu} does).
     */
    private void renderShopPage(GuiGraphicsExtractor graphics) {
        Font font = Minecraft.getInstance().font;
        int left = containerScreen.slotPanelLeft();
        int top = containerScreen.slotPanelTop();

        graphics.text(font, "Shop", left + SHOP_X, top + 4, 0xFFAAAAAA);
        graphics.text(font, "Buy", left + BUY_X, top + 4, 0xFFAAAAAA);
        graphics.text(font, "Sell", left + SELL_X, top + 4, 0xFFAAAAAA);

        String goldLine = "Merchant Gold: " + menu.shopGoldNuggets() + "    Player Gold: " + menu.playerGoldNuggets();
        graphics.text(font, goldLine, left + PLAYER_INV_X, top + 4, 0xFFFFD700);

        List<ShopListingEntry> listings = menu.listings();
        int offset = menu.scrollOffset();
        int visibleCount = Math.min(PlotConfigMenu.VISIBLE_SHOP_ROWS, Math.max(0, listings.size() - offset));
        for (int row = 0; row < visibleCount; row++) {
            ShopListingEntry listing = listings.get(offset + row);
            int rowTextY = top + SHOP_TOP_Y + row * SHOP_ROW_HEIGHT + SHOP_ROW_HEIGHT / 2 - 4;
            String priceLine = listing.shopStock() + " in stock   " + listing.pricePerUnit() + "g / " + listing.buyPricePerUnit() + "g";
            graphics.text(font, priceLine, left + SHOP_X + 20, rowTextY, 0xFFFFFFFF);
        }
        if (listings.size() > PlotConfigMenu.VISIBLE_SHOP_ROWS) {
            String scrollLabel = (offset + 1) + "-" + (offset + visibleCount) + " / " + listings.size();
            graphics.text(font, scrollLabel, left + SHOP_X, top + SHOP_TOP_Y + PlotConfigMenu.VISIBLE_SHOP_ROWS * SHOP_ROW_HEIGHT + 2, 0xFFAAAAAA);
        }

        int confirmY = top + SHOP_TOP_Y + PlotConfigMenu.VISIBLE_SHOP_ROWS * SHOP_ROW_HEIGHT + 12;
        String hint = "Shop <-> Buy, and Player <-> Sell only -- Confirm Purchase to charge/pay for everything queued.";
        graphics.text(font, Component.literal(hint).withStyle(ChatFormatting.GRAY), left + SHOP_X, confirmY - 12, 0xFFAAAAAA);
    }

    @Override
    public boolean mouseScrolled(Rect2i area, double mouseX, double mouseY, double scrollX, double scrollY) {
        if (subTab == SubTab.SHOP) {
            int left = containerScreen.slotPanelLeft();
            int top = containerScreen.slotPanelTop();
            if (scrollY != 0 && mouseX >= left + SHOP_X && mouseX < left + BUY_X
                    && mouseY >= top + SHOP_TOP_Y && mouseY < top + SHOP_TOP_Y + PlotConfigMenu.VISIBLE_SHOP_ROWS * SHOP_ROW_HEIGHT) {
                int buttonId = scrollY > 0 ? PlotConfigMenu.SCROLL_UP_BUTTON_ID : PlotConfigMenu.SCROLL_DOWN_BUTTON_ID;
                Minecraft.getInstance().gameMode.handleInventoryButtonClick(menu.containerId, buttonId);
                return true;
            }
        }
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

    private void send(CustomPacketPayload payload) {
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(payload));
    }
}
