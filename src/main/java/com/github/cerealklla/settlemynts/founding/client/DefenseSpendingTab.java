package com.github.cerealklla.settlemynts.founding.client;

import com.github.cerealklla.nuclyus.menu.client.NuclyusMenu;
import com.github.cerealklla.nuclyus.menu.client.WidgetHost;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;

/**
 * "Defense Spending" tab (Town Hall Core restyle, 2026-11-13, see {@link FoundingScreen}'s own
 * doc) -- a thin one-button wrapper that requests and opens the existing, unchanged {@link
 * DefenseSpendingScreen} exactly as the old flat "Defense Spending..." button did (a real server
 * round trip for the settlement's live Guardhouse list, same as before -- not folded in as inline
 * tab content, to avoid threading that async flow into this shared host). Disabled until the
 * settlement is finalized.
 */
final class DefenseSpendingTab implements NuclyusMenu {

    private final TownHallContext context;
    private WidgetHost widgetHost;
    private Button openButton;

    DefenseSpendingTab(TownHallContext context) {
        this.context = context;
    }

    @Override
    public Component title() {
        return Component.literal("Defense Spending");
    }

    @Override
    public int order() {
        return 3;
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
        openButton = widgetHost.addMenuWidget(Button.builder(Component.literal("Open Defense Spending..."), b -> context.openDefenseSpending())
                .bounds(area.getX() + area.getWidth() / 2 - 100, area.getY() + 20, 200, 20).build());
    }

    @Override
    public void onHidden() {
        if (openButton != null) {
            widgetHost.removeMenuWidget(openButton);
            openButton = null;
        }
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, Rect2i area, int mouseX, int mouseY, float partialTick) {
        String message = "Allocate gold top-ups to this settlement's Guardhouses.";
        var font = Minecraft.getInstance().font;
        graphics.text(font, message, area.getX() + area.getWidth() / 2 - font.width(message) / 2, area.getY() + 54, 0xFFAAAAAA);
    }
}
