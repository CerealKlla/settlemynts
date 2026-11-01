package com.github.cerealklla.settlemynts.plotsign.client;

import com.github.cerealklla.nuclyus.menu.client.NuclyusMenu;
import com.github.cerealklla.nuclyus.menu.client.WidgetHost;
import com.github.cerealklla.settlemynts.plotsign.OpenPlotConfigSignMenuPayload;
import com.github.cerealklla.settlemynts.tavern.RequestTavernStoolsPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

/**
 * "Tavern" tab -- design doc Section 17. Direct mirror of {@code GarrisonTab}; only present when
 * {@code canManage && isTavern}. The stools themselves still own all their own state (seated NPC,
 * hire requirement) via their own in-world right-click interaction, unaffected by this tab.
 *
 * <p><b>Tracks its own remaining count locally</b> (2026-10-31 fix, real report: clicking "Get
 * Tavern Stools" didn't make the button go away until leaving and re-entering the tab) -- {@code
 * data} is a one-shot snapshot from when the sign was opened and is never refreshed by the server
 * granting items, so without this the button/message stayed stale for the rest of this GUI session.
 * The server always grants exactly {@code data.tavernStoolsAvailable()} in one go (see {@code
 * SettlemyntsMod#requestTavernStools}), so optimistically zeroing the local count on click is exact,
 * not just an approximation.
 */
public final class TavernTab implements NuclyusMenu {

    private final OpenPlotConfigSignMenuPayload data;
    private int remainingAvailable;
    private WidgetHost host;
    private Button getStoolsButton;

    public TavernTab(OpenPlotConfigSignMenuPayload data) {
        this.data = data;
        this.remainingAvailable = data.tavernStoolsAvailable();
    }

    @Override
    public Component title() {
        return Component.literal("Tavern");
    }

    @Override
    public int order() {
        return 5;
    }

    @Override
    public void attachHost(Screen host) {
        this.host = (WidgetHost) host;
    }

    @Override
    public void onShown() {
        if (remainingAvailable > 0) {
            Rect2i area = host.contentArea();
            getStoolsButton = host.addMenuWidget(Button.builder(
                            Component.literal("Get Tavern Stools (" + remainingAvailable + ")"), b -> getStools())
                    .bounds(area.getX() + area.getWidth() / 2 - 100, area.getY() + 20, 200, 20).build());
        }
    }

    @Override
    public void onHidden() {
        if (getStoolsButton != null) {
            host.removeMenuWidget(getStoolsButton);
            getStoolsButton = null;
        }
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, Rect2i area, int mouseX, int mouseY, float partialTick) {
        if (remainingAvailable <= 0) {
            net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
            String message = "No more Tavern Stools available at this plot's current Tier.";
            int centerX = area.getX() + area.getWidth() / 2;
            graphics.text(font, message, centerX - font.width(message) / 2, area.getY() + 20, 0xFFAAAAAA);
        }
    }

    private void getStools() {
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(new RequestTavernStoolsPayload(data.signPos())));
        remainingAvailable = 0;
        if (getStoolsButton != null) {
            host.removeMenuWidget(getStoolsButton);
            getStoolsButton = null;
        }
    }
}
