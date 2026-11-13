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
 * "Color & Banner" tab (Town Hall Core restyle, 2026-11-13, see {@link FoundingScreen}'s own doc)
 * -- a thin one-button wrapper that requests and opens the existing, unchanged {@link
 * ColorAndBannerScreen} exactly as the old flat "Capital City Keep.../Settlement Hall..." button
 * did. Disabled until the settlement is finalized.
 */
final class ColorAndBannerTab implements NuclyusMenu {

    private final TownHallContext context;
    private WidgetHost widgetHost;
    private Button openButton;

    ColorAndBannerTab(TownHallContext context) {
        this.context = context;
    }

    @Override
    public Component title() {
        return Component.literal("Color & Banner");
    }

    @Override
    public int order() {
        return 4;
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
        String label = context.isCapitalCity() ? "Open Capital City Keep..." : "Open Settlement Hall...";
        openButton = widgetHost.addMenuWidget(Button.builder(Component.literal(label), b -> context.openColorAndBannerScreen())
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
        String message = "Pick the settlement's color and design its banner" + (context.isCapitalCity() ? " and Kingdom Banner." : ".");
        var font = Minecraft.getInstance().font;
        graphics.text(font, message, area.getX() + area.getWidth() / 2 - font.width(message) / 2, area.getY() + 54, 0xFFAAAAAA);
    }
}
