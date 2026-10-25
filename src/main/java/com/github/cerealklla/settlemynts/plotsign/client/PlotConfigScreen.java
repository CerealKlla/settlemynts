package com.github.cerealklla.settlemynts.plotsign.client;

import java.util.ArrayList;
import java.util.List;

import com.github.cerealklla.nuclyus.menu.client.NuclyusContainerScreen;
import com.github.cerealklla.nuclyus.menu.client.NuclyusMenu;
import com.github.cerealklla.settlemynts.plotsign.PlotConfigMenu;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * The Plot Config Sign's real screen (merged 2026-10-24, explicit request: "Place it fully within
 * the unified menu, not as a separate area" -- re: the Shop tab previously popping its own separate
 * {@code ShopCartMenu} window). Rebuilt from a static factory producing a plain {@code
 * NuclyusMenuScreen} into a real class extending {@link NuclyusContainerScreen}, backed by {@link
 * PlotConfigMenu} -- see that class's own doc for the full merge design (real Shop/Buy/Sell/Player
 * slots embedded alongside the same Overview/Upgrade/Configure/Shop/Garrison/Management tabs this
 * screen always had, inert outside the Shop tab's own Shop sub-tab).
 *
 * <p>{@link #imageWidth}/{@link #imageHeight} (the fixed, centered box {@code AbstractContainerScreen}
 * uses for the real slot panel) are sized to exactly fit the Shop lanes, same as the old {@code
 * ShopCartScreen} -- this does **not** constrain the other five tabs at all, since {@link
 * NuclyusContainerScreen#contentArea()} spans the full window regardless of {@code imageWidth}/
 * {@code imageHeight}, same as it always did under plain {@code NuclyusMenuScreen}.
 */
public final class PlotConfigScreen extends NuclyusContainerScreen<PlotConfigMenu> {

    private static final int TOP_Y = 18;
    private static final int ROW_HEIGHT = 18;
    private static final int PLAYER_INV_X = 300;

    public PlotConfigScreen(PlotConfigMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, buildTabs(menu), PLAYER_INV_X + 9 * 18 + 10,
                TOP_Y + PlotConfigMenu.VISIBLE_SHOP_ROWS * ROW_HEIGHT + 56);
        this.inventoryLabelY = -1000; // Suppress vanilla's auto "Inventory" label -- player inv has no separate panel here, same as the old ShopCartScreen.
        // Suppress vanilla's auto title label too, 2026-10-24 -- it was never moved to account for
        // ShopTab's own second tab tier and rendered right on top of it (misread live as "ShopConfig"
        // fused with the "Shop" sub-tab button beneath it). Every tab already carries its own heading
        // via the tab bar(s) themselves; a floating "Plot Config" string added nothing real.
        this.titleLabelY = -1000;
    }

    /**
     * Clears {@link ShopTab}'s own second tab tier, whenever it can be showing, so the real Shop/
     * Buy/Sell/Player slot panel (and {@code ShopTab}'s own header text drawn relative to {@link
     * #slotPanelTop()}) never sits underneath it -- see {@link NuclyusContainerScreen#extraTopOffset()}'s
     * own doc for why this has to be this hook and not a plain {@code init()} override. Gated on
     * {@code canManage} to match {@code ShopTab#visibleSubTabs()} exactly -- a non-manager viewer
     * never sees that second tier at all (only "Shop" exists for them), so nothing needs the extra
     * clearance in that case.
     */
    @Override
    protected int extraTopOffset() {
        return menu.hasShop() && menu.signData().canManage() ? ShopTab.SUB_TAB_BAR_HEIGHT : 0;
    }

    private static List<NuclyusMenu> buildTabs(PlotConfigMenu menu) {
        var data = menu.signData();
        List<NuclyusMenu> tabs = new ArrayList<>();
        tabs.add(new OverviewTab(data));
        if (data.canManage() && data.hasConstructionBox() && data.tier() < 5 && data.tier() < data.maxPlotTier()) {
            tabs.add(new UpgradeTab(data));
        }
        if (data.canManage()) {
            tabs.add(new ConfigureTab(data));
        }
        if (menu.hasShop()) {
            tabs.add(new ShopTab(data, menu));
        }
        if (data.canManage() && data.hasGarrison()) {
            tabs.add(new GarrisonTab(data));
        }
        if (data.canManage() && data.isTownHall()) {
            tabs.add(new ManagementTab(data));
        }
        return tabs;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xC0101010);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** See the old {@code ShopCartScreen}'s own doc -- same "citing the Kyt editor screen" fix, same reasoning, unchanged. */
    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int xo, int yo) {
        return false;
    }
}
