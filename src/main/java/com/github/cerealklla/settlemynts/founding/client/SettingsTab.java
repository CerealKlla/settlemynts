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
 * "Settings" tab (Town Hall Core restyle, 2026-11-13, see {@link FoundingScreen}'s own doc) --
 * sales tax per item sold (any Town Planner) and required Blueprint tags (Mayor-only, backlog
 * item 5) -- both previously the last two rows of the old flat {@code FoundingScreen}, only shown
 * once finalized. Disabled until then, same gating they already had.
 */
final class SettingsTab implements NuclyusMenu {

    private final TownHallContext context;
    private WidgetHost widgetHost;
    private final List<GuiEventListener> ownedWidgets = new ArrayList<>();
    private EditBox taxBox;
    private EditBox requiredTagsBox;

    SettingsTab(TownHallContext context) {
        this.context = context;
    }

    @Override
    public Component title() {
        return Component.literal("Settings");
    }

    @Override
    public int order() {
        return 5;
    }

    @Override
    public boolean enabled() {
        return context.finalized();
    }

    @Override
    public Component disabledReason() {
        return Component.literal("Finalize the settlement first");
    }

    @Override
    public void attachHost(Screen host) {
        this.widgetHost = (WidgetHost) host;
    }

    @Override
    public void onShown() {
        Rect2i area = widgetHost.contentArea();
        int centerX = area.getX() + area.getWidth() / 2;
        int y = area.getY() + 20;

        taxBox = add(new EditBox(Minecraft.getInstance().font, centerX - 100, y, 150, 20, Component.literal("Tax per item sold")));
        taxBox.setMaxLength(9);
        taxBox.setValue(Integer.toString(context.initialTaxPerItem()));
        add(Button.builder(Component.literal("Set Tax"), b -> setTax())
                .bounds(centerX + 55, y, 90, 20).build());
        y += 30;

        if (context.viewerIsFounder()) {
            requiredTagsBox = add(new EditBox(Minecraft.getInstance().font, centerX - 100, y, 150, 20, Component.literal("Required tags")));
            requiredTagsBox.setMaxLength(256);
            requiredTagsBox.setValue(context.initialRequiredTags());
            requiredTagsBox.setHint(Component.literal("tags, comma, separated"));
            add(Button.builder(Component.literal("Set Tags"), b -> context.setRequiredTags(requiredTagsBox.getValue()))
                    .bounds(centerX + 55, y, 90, 20).build());
        }
    }

    @Override
    public void onHidden() {
        for (GuiEventListener widget : ownedWidgets) {
            widgetHost.removeMenuWidget(widget);
        }
        ownedWidgets.clear();
        taxBox = null;
        requiredTagsBox = null;
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, Rect2i area, int mouseX, int mouseY, float partialTick) {
        // Both fields are self-explanatory via their own EditBox hint/label -- no extra body text.
    }

    private void setTax() {
        if (taxBox == null) {
            return;
        }
        try {
            int value = Integer.parseInt(taxBox.getValue().trim());
            context.setTax(value);
        } catch (NumberFormatException ignored) {
            // Non-numeric input -- same silent-ignore shape the pre-restyle screen already used.
        }
    }

    private <T extends GuiEventListener & Renderable & NarratableEntry> T add(T widget) {
        widgetHost.addMenuWidget(widget);
        ownedWidgets.add(widget);
        return widget;
    }
}
