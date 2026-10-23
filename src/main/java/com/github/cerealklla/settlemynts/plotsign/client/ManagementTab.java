package com.github.cerealklla.settlemynts.plotsign.client;

import com.github.cerealklla.nuclyus.menu.client.NuclyusMenu;
import com.github.cerealklla.nuclyus.menu.client.NuclyusMenuScreen;
import com.github.cerealklla.settlemynts.plotsign.ClientPlotSignRequests;
import com.github.cerealklla.settlemynts.plotsign.OpenPlotConfigSignMenuPayload;
import com.github.cerealklla.settlemynts.plotsign.OpenPlotManagementPayload;
import com.github.cerealklla.settlemynts.plotsign.PlotSummaryEntry;
import com.github.cerealklla.settlemynts.plotsign.RequestEstablishHighwayMapPayload;
import com.github.cerealklla.settlemynts.plotsign.RequestPlotManagementPayload;

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
 * "Management" tab (tabbed Plot Config Sign rework, 2026-10-23) -- only present when {@code
 * canManage && isTownHall} (the Town Hall plot's own sign only). Folds in the old flat {@code
 * client.PlotManagementScreen}'s settlement-wide admin list (read-only, no scrolling yet -- same v1
 * limitation that screen already flagged) plus the "Establish Highway" button, which still pops the
 * real {@code EstablishHighwayMapScreen} over this whole tabbed host.
 */
public final class ManagementTab implements NuclyusMenu {

    private final OpenPlotConfigSignMenuPayload data;
    private NuclyusMenuScreen host;
    private OpenPlotManagementPayload managementData;
    private Button establishHighwayButton;

    public ManagementTab(OpenPlotConfigSignMenuPayload data) {
        this.data = data;
    }

    @Override
    public Component title() {
        return Component.literal("Management");
    }

    @Override
    public int order() {
        return 6;
    }

    @Override
    public void attachHost(Screen host) {
        this.host = (NuclyusMenuScreen) host;
    }

    @Override
    public void onShown() {
        managementData = null;
        send(new RequestPlotManagementPayload(data.signPos()));

        Rect2i area = host.contentArea();
        establishHighwayButton = host.addMenuWidget(Button.builder(Component.literal("Establish Highway"), b -> establishHighway())
                .bounds(area.getX() + area.getWidth() / 2 - 100, area.getY() + area.getHeight() - 30, 200, 20).build());
    }

    @Override
    public void onHidden() {
        if (establishHighwayButton != null) {
            host.removeMenuWidget(establishHighwayButton);
            establishHighwayButton = null;
        }
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, Rect2i area, int mouseX, int mouseY, float partialTick) {
        ClientPlotSignRequests.takePendingPlotManagement().ifPresent(payload -> managementData = payload);

        Font font = Minecraft.getInstance().font;
        int centerX = area.getX() + area.getWidth() / 2;
        int y = area.getY() + 20;
        if (managementData == null) {
            String message = "Loading...";
            graphics.text(font, message, centerX - font.width(message) / 2, y, 0xFFAAAAAA);
            return;
        }
        if (managementData.plots().isEmpty()) {
            String message = "No plots in this settlement yet.";
            graphics.text(font, message, centerX - font.width(message) / 2, y, 0xFFAAAAAA);
            return;
        }
        for (PlotSummaryEntry entry : managementData.plots()) {
            String headline = entry.plotName() + " -- Owner: " + entry.ownerDisplay() + " -- " + entry.billingStanding();
            graphics.text(font, headline, centerX - font.width(headline) / 2, y, 0xFFFFFFFF);
            y += 12;
            if (entry.issue().isPresent()) {
                String issue = entry.issue().get();
                graphics.text(font, issue, centerX - font.width(issue) / 2, y, 0xFFFF5555);
            }
            y += 14;
        }
    }

    private void establishHighway() {
        send(new RequestEstablishHighwayMapPayload(data.signPos()));
        // Closes this whole tabbed host -- same "screen == null guard" convention as OverviewTab's
        // Upgrade Plot button (see that class's own doc).
        Minecraft.getInstance().setScreen(null);
    }

    private void send(CustomPacketPayload payload) {
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(payload));
    }
}
