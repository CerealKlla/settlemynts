package com.github.cerealklla.settlemynts.plotsign.client;

import com.github.cerealklla.nuclyus.menu.client.NuclyusMenu;
import com.github.cerealklla.nuclyus.menu.client.NuclyusMenuScreen;
import com.github.cerealklla.settlemynts.guardhouse.RequestGuardKitStandsPayload;
import com.github.cerealklla.settlemynts.plotsign.OpenPlotConfigSignMenuPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

/**
 * "Garrison" tab (tabbed Plot Config Sign rework, 2026-10-23) -- only present when {@code canManage
 * && hasGarrison}. Folds in the old flat Main Menu's "Get Guard Kit Stands" button; the Guard Kit
 * Stand items themselves still own all their own configuration (Kyt binding, spares count, etc.)
 * via their own in-world right-click Kit Menu, unaffected by this rework.
 */
public final class GarrisonTab implements NuclyusMenu {

    private final OpenPlotConfigSignMenuPayload data;
    private NuclyusMenuScreen host;
    private Button getStandsButton;

    public GarrisonTab(OpenPlotConfigSignMenuPayload data) {
        this.data = data;
    }

    @Override
    public Component title() {
        return Component.literal("Garrison");
    }

    @Override
    public int order() {
        return 5;
    }

    @Override
    public void attachHost(Screen host) {
        this.host = (NuclyusMenuScreen) host;
    }

    @Override
    public void onShown() {
        if (data.guardKitStandsAvailable() > 0) {
            Rect2i area = host.contentArea();
            getStandsButton = host.addMenuWidget(Button.builder(
                            Component.literal("Get Guard Kit Stands (" + data.guardKitStandsAvailable() + ")"), b -> getStands())
                    .bounds(area.getX() + area.getWidth() / 2 - 100, area.getY() + 20, 200, 20).build());
        }
    }

    @Override
    public void onHidden() {
        if (getStandsButton != null) {
            host.removeMenuWidget(getStandsButton);
            getStandsButton = null;
        }
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, Rect2i area, int mouseX, int mouseY, float partialTick) {
        if (data.guardKitStandsAvailable() <= 0) {
            net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
            String message = "No more Guard Kit Stands available at this plot's current Tier.";
            int centerX = area.getX() + area.getWidth() / 2;
            graphics.text(font, message, centerX - font.width(message) / 2, area.getY() + 20, 0xFFAAAAAA);
        }
    }

    private void getStands() {
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(new RequestGuardKitStandsPayload(data.signPos())));
    }
}
