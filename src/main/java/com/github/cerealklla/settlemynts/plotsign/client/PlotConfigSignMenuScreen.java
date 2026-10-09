package com.github.cerealklla.settlemynts.plotsign.client;

import com.github.cerealklla.settlemynts.guardhouse.RequestConfigureGarrisonPayload;
import com.github.cerealklla.settlemynts.plotsign.OpenPlotConfigSignMenuPayload;
import com.github.cerealklla.settlemynts.plotsign.RequestPlotDetailsPayload;
import com.github.cerealklla.settlemynts.plotsign.RequestPlotManagementPayload;
import com.github.cerealklla.settlemynts.plotsign.RequestShopPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * The Plot Config Sign's Main Menu (design doc Section 14a) -- opened via {@link
 * OpenPlotConfigSignMenuPayload}. "Configure Plot" only shows once {@code canManage} (resolved
 * server-side) is true; "Enter Shop"/"Manage Shop" open a real {@code client.ShopScreen} as of
 * 2026-10-05 (see {@code RequestShopPayload}'s own doc) -- but hidden
 * entirely for a Guardhouse plot (real report, 2026-09-30: "The Guardhouse Plot Sign shouldn't have
 * Shop buttons" -- a Guardhouse is NPC-owned infrastructure, not a shop-bearing plot) and, same
 * reasoning, for a Town Hall plot (real report, 2026-10-05 -- civic infrastructure, not a
 * shop-bearing plot; see {@code PlotConfigSignBlock#hasShop}, the matching server-side gate).
 * "Configure
 * Garrison" (added 2026-09-30, Guardhouse Plot Type) shows only when {@code hasGarrison} is true,
 * same {@code canManage}-only visibility as "Configure Plot"/"Manage Shop".
 */
public final class PlotConfigSignMenuScreen extends Screen {

    private final OpenPlotConfigSignMenuPayload data;

    public PlotConfigSignMenuScreen(OpenPlotConfigSignMenuPayload data) {
        super(Component.literal("Plot Config Sign"));
        this.data = data;
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int y = 50;

        if (data.canManage()) {
            addRenderableWidget(Button.builder(Component.literal("Configure Plot"),
                    b -> Minecraft.getInstance().setScreen(new PlotConfigureScreen(data.signPos())))
                    .bounds(centerX - 100, y, 200, 20).build());
            y += 30;
        }

        if (!data.hasGarrison() && !data.isTownHall()) {
            addRenderableWidget(Button.builder(Component.literal("Enter Shop"), b -> requestShop(false))
                    .bounds(centerX - 100, y, 200, 20).build());
            y += 30;

            if (data.canManage()) {
                addRenderableWidget(Button.builder(Component.literal("Manage Shop"), b -> requestShop(true))
                        .bounds(centerX - 100, y, 200, 20).build());
                y += 30;
            }
        }

        if (data.canManage() && data.hasGarrison()) {
            addRenderableWidget(Button.builder(Component.literal("Configure Garrison"), b -> configureGarrison())
                    .bounds(centerX - 100, y, 200, 20).build());
            y += 30;
        }

        addRenderableWidget(Button.builder(Component.literal("Plot Details"), b -> plotDetails())
                .bounds(centerX - 100, y, 200, 20).build());
        y += 30;

        if (data.canManage() && data.isTownHall()) {
            addRenderableWidget(Button.builder(Component.literal("Plot Management"), b -> plotManagement())
                    .bounds(centerX - 100, y, 200, 20).build());
            y += 30;
        }

        y += 10;
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(centerX - 50, y, 100, 20).build());
    }

    private void requestShop(boolean manage) {
        send(new RequestShopPayload(new com.github.cerealklla.settlemynts.plotsign.ShopAnchor.Sign(data.signPos()), manage));
        onClose();
    }

    private void configureGarrison() {
        send(new RequestConfigureGarrisonPayload(data.signPos()));
        onClose();
    }

    private void plotDetails() {
        send(new RequestPlotDetailsPayload(data.signPos()));
        onClose();
    }

    private void plotManagement() {
        send(new RequestPlotManagementPayload(data.signPos()));
        onClose();
    }

    private void send(CustomPacketPayload payload) {
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(payload));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int titleWidth = font.width(title);
        graphics.text(font, title, width / 2 - titleWidth / 2, 20, 0xFFFFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
