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
 */
public final class TavernTab implements NuclyusMenu {

    private final OpenPlotConfigSignMenuPayload data;
    private WidgetHost host;
    private Button getStoolsButton;

    public TavernTab(OpenPlotConfigSignMenuPayload data) {
        this.data = data;
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
        if (data.tavernStoolsAvailable() > 0) {
            Rect2i area = host.contentArea();
            getStoolsButton = host.addMenuWidget(Button.builder(
                            Component.literal("Get Tavern Stools (" + data.tavernStoolsAvailable() + ")"), b -> getStools())
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
        if (data.tavernStoolsAvailable() <= 0) {
            net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
            String message = "No more Tavern Stools available at this plot's current Tier.";
            int centerX = area.getX() + area.getWidth() / 2;
            graphics.text(font, message, centerX - font.width(message) / 2, area.getY() + 20, 0xFFAAAAAA);
        }
    }

    private void getStools() {
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(new RequestTavernStoolsPayload(data.signPos())));
    }
}
