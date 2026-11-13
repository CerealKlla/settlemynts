package com.github.cerealklla.settlemynts.founding.client;

import java.util.ArrayList;
import java.util.List;

import com.github.cerealklla.nuclyus.menu.client.NuclyusMenu;
import com.github.cerealklla.nuclyus.menu.client.WidgetHost;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;

/**
 * "Overview" tab (Town Hall Core restyle, 2026-11-13, see {@link FoundingScreen}'s own doc) --
 * settlement name, Town Planner grant/list, the boundary-visibility toggle, and (pre-finalize
 * only) Get Perimeter Stake/Finalize. Always the first, always-enabled tab -- everything a Mayor
 * needs regardless of whether the settlement is finalized yet.
 */
final class OverviewTab implements NuclyusMenu {

    private final TownHallContext context;
    private Screen hostScreen;
    private WidgetHost widgetHost;

    private final List<GuiEventListener> ownedWidgets = new ArrayList<>();
    private EditBox nameBox;
    private EditBox grantBox;
    private Button boundaryToggleButton;
    private int plannerListY;

    OverviewTab(TownHallContext context) {
        this.context = context;
    }

    @Override
    public Component title() {
        return Component.literal("Overview");
    }

    @Override
    public int order() {
        return 0;
    }

    @Override
    public void attachHost(Screen host) {
        this.hostScreen = host;
        this.widgetHost = (WidgetHost) host;
    }

    @Override
    public void onShown() {
        Rect2i area = widgetHost.contentArea();
        int centerX = area.getX() + area.getWidth() / 2;
        int y = area.getY() + 16;

        nameBox = add(new EditBox(Minecraft.getInstance().font, centerX - 100, y, 150, 20, Component.literal("Settlement name")));
        nameBox.setMaxLength(64);
        nameBox.setValue(context.initialName());
        add(Button.builder(Component.literal("Set Name"), b -> context.setName(nameBox.getValue()))
                .bounds(centerX + 55, y, 90, 20).build());
        y += 30;

        // "Get Perimeter Stake"/"Finalize" only make sense pre-finalize -- the finalized-settlement
        // replacements (Plots/Roadways/Defense Spending/Color & Banner) are their own tabs now.
        if (!context.finalized()) {
            add(Button.builder(Component.literal("Get Perimeter Stake"), b -> context.getStake())
                    .bounds(centerX - 100, y, 245, 20).build());
            y += 30;
        }

        boundaryToggleButton = add(Button.builder(boundaryLabel(), b -> toggleBoundary())
                .bounds(centerX - 100, y, 245, 20).build());
        y += 30;

        if (context.viewerIsFounder()) {
            grantBox = add(new EditBox(Minecraft.getInstance().font, centerX - 100, y, 150, 20, Component.literal("Player name")));
            grantBox.setMaxLength(16);
            add(Button.builder(Component.literal("Grant Planner"), b -> context.grantPlanner(grantBox.getValue()))
                    .bounds(centerX + 55, y, 90, 20).build());
            y += 30;
        }

        if (!context.finalized()) {
            add(Button.builder(Component.literal("Finalize"), b -> context.finalizeSettlement())
                    .bounds(centerX - 100, y, 245, 20).build());
            y += 30;
        }

        add(Button.builder(Component.literal("Close"), b -> hostScreen.onClose())
                .bounds(centerX - 50, y, 100, 20).build());

        // Rendered below every widget (see render()) -- computed here so it never overlaps the
        // button stack above regardless of which optional rows (pre-finalize controls, the
        // founder-only grant row) are present. Same real layout-bug fix this screen already had
        // before the restyle, just recomputed per-tab now.
        plannerListY = y + 34;
    }

    @Override
    public void onHidden() {
        for (GuiEventListener widget : ownedWidgets) {
            widgetHost.removeMenuWidget(widget);
        }
        ownedWidgets.clear();
        nameBox = null;
        grantBox = null;
        boundaryToggleButton = null;
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, Rect2i area, int mouseX, int mouseY, float partialTick) {
        int centerX = area.getX() + area.getWidth() / 2;
        var font = Minecraft.getInstance().font;

        int listY = plannerListY;
        String header = "Town Planners:";
        graphics.text(font, header, centerX - 100, listY, 0xFFAAAAAA);
        listY += 12;
        for (String name : context.townPlannerNames()) {
            graphics.text(font, name, centerX - 100, listY, 0xFFFFFFFF);
            listY += 12;
        }
    }

    private void toggleBoundary() {
        boolean nowVisible = context.toggleBoundaryVisible();
        boundaryToggleButton.setMessage(boundaryLabelFor(nowVisible));
    }

    private Component boundaryLabel() {
        return boundaryLabelFor(context.boundaryVisible());
    }

    private Component boundaryLabelFor(boolean visible) {
        return Component.literal("View Settlement Boundaries: " + (visible ? "ON" : "OFF"));
    }

    private <T extends GuiEventListener & Renderable & NarratableEntry> T add(T widget) {
        widgetHost.addMenuWidget(widget);
        ownedWidgets.add(widget);
        return widget;
    }
}
