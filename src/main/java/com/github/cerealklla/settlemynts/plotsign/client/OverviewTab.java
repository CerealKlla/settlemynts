package com.github.cerealklla.settlemynts.plotsign.client;

import com.github.cerealklla.nuclyus.menu.client.NuclyusMenu;
import com.github.cerealklla.nuclyus.menu.client.NuclyusMenuScreen;
import com.github.cerealklla.settlemynts.plotsign.ClientPlotSignRequests;
import com.github.cerealklla.settlemynts.plotsign.OpenPlotConfigSignMenuPayload;
import com.github.cerealklla.settlemynts.plotsign.OpenPlotDetailsPayload;
import com.github.cerealklla.settlemynts.plotsign.RequestHireNpcPayload;
import com.github.cerealklla.settlemynts.plotsign.RequestPlotDetailsPayload;
import com.github.cerealklla.settlemynts.plotsign.RequestUpgradePlotPreviewPayload;

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
 * the old flat "Plot Details" screen in as read-only content, plus the "Hire NPC"/"Upgrade Plot"
 * actions that used to be separate Main Menu buttons. "Upgrade Plot" still pops the real {@code
 * UpgradePlotScreen} over this whole tabbed host (same as the old Main Menu button did) rather than
 * becoming inline content -- its own funding-option buttons are {@code .active}-gated from a server
 * round trip, not worth folding into tab content for a one-shot action that replaces the screen
 * either way.
 */
public final class OverviewTab implements NuclyusMenu {

    private final OpenPlotConfigSignMenuPayload data;
    private NuclyusMenuScreen host;
    private OpenPlotDetailsPayload details;
    private Button hireNpcButton;
    private Button upgradePlotButton;

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
        int y = area.getY() + 20;
        if (data.canHireNpc()) {
            hireNpcButton = host.addMenuWidget(Button.builder(Component.literal("Hire NPC"), b -> hireNpc())
                    .bounds(area.getX() + 20, y, 150, 20).build());
            y += 24;
        }
        if (data.canManage() && data.hasConstructionBox() && data.tier() < 5 && data.tier() < data.maxPlotTier()) {
            upgradePlotButton = host.addMenuWidget(Button.builder(Component.literal("Upgrade Plot"), b -> upgradePlot())
                    .bounds(area.getX() + 20, y, 150, 20).build());
        }
    }

    @Override
    public void onHidden() {
        if (hireNpcButton != null) {
            host.removeMenuWidget(hireNpcButton);
            hireNpcButton = null;
        }
        if (upgradePlotButton != null) {
            host.removeMenuWidget(upgradePlotButton);
            upgradePlotButton = null;
        }
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, Rect2i area, int mouseX, int mouseY, float partialTick) {
        ClientPlotSignRequests.takePendingPlotDetails().ifPresent(p -> details = p);

        Font font = Minecraft.getInstance().font;
        int centerX = area.getX() + area.getWidth() / 2;
        int y = area.getY() + 20 + (hireNpcButton != null ? 24 : 0) + (upgradePlotButton != null ? 24 : 0) + 16;
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

    private void upgradePlot() {
        send(new RequestUpgradePlotPreviewPayload(data.signPos()));
        // Closes this whole tabbed host -- the central dispatcher in SettlemyntsModClient only opens
        // UpgradePlotScreen once the response arrives if `screen == null`, same convention as every
        // other "pop a real sub-screen" action in this feature.
        Minecraft.getInstance().setScreen(null);
    }

    private void send(CustomPacketPayload payload) {
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(payload));
    }
}
