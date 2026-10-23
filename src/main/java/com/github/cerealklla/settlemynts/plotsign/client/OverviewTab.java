package com.github.cerealklla.settlemynts.plotsign.client;

import com.github.cerealklla.nuclyus.menu.client.NuclyusMenu;
import com.github.cerealklla.nuclyus.menu.client.NuclyusMenuScreen;
import com.github.cerealklla.settlemynts.plotsign.ClientPlotSignRequests;
import com.github.cerealklla.settlemynts.plotsign.OpenPlotConfigSignMenuPayload;
import com.github.cerealklla.settlemynts.plotsign.OpenPlotDetailsPayload;
import com.github.cerealklla.settlemynts.plotsign.RequestHireNpcPayload;
import com.github.cerealklla.settlemynts.plotsign.RequestPlotDetailsPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * "Overview" tab (tabbed Plot Config Sign rework, 2026-10-23) -- always present, first tab. Folds
 * the old flat "Plot Details" screen in as read-only content, plus the "Hire NPC" action that used
 * to be a separate Main Menu button. "Upgrade Plot" now lives in its own {@link UpgradeTab} (folded
 * in from the old pop-up {@code client.UpgradePlotScreen} per explicit user request, same day) --
 * its cost breakdown + 3 funding buttons needed a real content area of their own, not a shared
 * strip here.
 */
public final class OverviewTab implements NuclyusMenu {

    private final OpenPlotConfigSignMenuPayload data;
    private NuclyusMenuScreen host;
    private OpenPlotDetailsPayload details;
    private Button hireNpcButton;

    public OverviewTab(OpenPlotConfigSignMenuPayload data) {
        this.data = data;
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
        this.host = (NuclyusMenuScreen) host;
    }

    @Override
    public void onShown() {
        details = null;
        send(new RequestPlotDetailsPayload(data.signPos()));

        Rect2i area = host.contentArea();
        if (data.canHireNpc()) {
            hireNpcButton = host.addMenuWidget(Button.builder(Component.literal("Hire NPC"), b -> hireNpc())
                    .bounds(area.getX() + 20, area.getY() + 20, 150, 20).build());
        }
    }

    @Override
    public void onHidden() {
        if (hireNpcButton != null) {
            host.removeMenuWidget(hireNpcButton);
            hireNpcButton = null;
        }
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, Rect2i area, int mouseX, int mouseY, float partialTick) {
        ClientPlotSignRequests.takePendingPlotDetails().ifPresent(p -> details = p);

        Font font = Minecraft.getInstance().font;
        int centerX = area.getX() + area.getWidth() / 2;
        int y = area.getY() + 20 + (hireNpcButton != null ? 24 : 0) + 16;
        if (details == null) {
            String message = "Loading...";
            graphics.text(font, message, centerX - font.width(message) / 2, y, 0xFFAAAAAA);
            return;
        }
        y = line(graphics, font, centerX, "Plot: " + details.entry().plotName(), y, 0xFFFFFFFF);
        y = line(graphics, font, centerX, "Owner: " + details.entry().ownerDisplay(), y, 0xFFFFFFFF);
        y = line(graphics, font, centerX, "Billing Standing: " + details.entry().billingStanding(), y, 0xFFFFFFFF);
        if (details.entry().issue().isPresent()) {
            line(graphics, font, centerX, "Issue: " + details.entry().issue().get(), y, 0xFFFF5555);
        }
    }

    private int line(GuiGraphicsExtractor graphics, Font font, int centerX, String text, int y, int color) {
        graphics.text(font, text, centerX - font.width(text) / 2, y, color);
        return y + 14;
    }

    private void hireNpc() {
        send(new RequestHireNpcPayload(data.signPos()));
    }

    private void send(CustomPacketPayload payload) {
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(payload));
    }
}
