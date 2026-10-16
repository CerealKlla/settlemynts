package com.github.cerealklla.settlemynts.plotsign.client;

import com.github.cerealklla.settlemynts.guardhouse.RequestGuardKitStandsPayload;
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
 * "Get Guard Kit Stands" (added 2026-09-30, Guardhouse Plot Type; reworked 2026-10-16 from the old
 * "Configure Garrison" into granting real, placeable Guard Kit Stand items -- see {@code
 * guardhouse.GuardKitStandEntity}) shows only when {@code hasGarrison} is true and at least one more
 * stand is available ({@code guardKitStandsAvailable > 0}), same {@code canManage}-only visibility as
 * "Configure Plot"/"Manage Shop".
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

                addRenderableWidget(Button.builder(Component.literal("Planned Inventory"), b -> plannedInventory())
                        .bounds(centerX - 100, y, 200, 20).build());
                y += 30;
            }
        }

        if (data.canManage() && data.hasGarrison() && data.guardKitStandsAvailable() > 0) {
            addRenderableWidget(Button.builder(Component.literal("Get Guard Kit Stands (" + data.guardKitStandsAvailable() + ")"), b -> getGuardKitStands())
                    .bounds(centerX - 100, y, 200, 20).build());
            y += 30;
        }

        if (data.canManage() && data.hasConstructionBox() && data.tier() < 5 && data.tier() < data.maxPlotTier()) {
            addRenderableWidget(Button.builder(Component.literal("Upgrade Plot"), b -> upgradePlot())
                    .bounds(centerX - 100, y, 200, 20).build());
            y += 30;
        }

        if (data.canHireNpc()) {
            addRenderableWidget(Button.builder(Component.literal("Hire NPC"), b -> hireNpc())
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

            // Shown to any Town Planner who can manage this plot -- the stricter Mayor(founder)-only
            // check happens server-side on click, same "visible but re-validated" discipline as every
            // other button here (e.g. "Upgrade Plot" re-checks canManage itself too).
            addRenderableWidget(Button.builder(Component.literal("Establish Highway"), b -> establishHighway())
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

    private void plannedInventory() {
        send(new com.github.cerealklla.settlemynts.plotsign.RequestPlannedInventoryPayload(data.signPos()));
        onClose();
    }

    private void getGuardKitStands() {
        send(new RequestGuardKitStandsPayload(data.signPos()));
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

    private void upgradePlot() {
        send(new com.github.cerealklla.settlemynts.plotsign.RequestUpgradePlotPreviewPayload(data.signPos()));
        onClose();
    }

    private void hireNpc() {
        send(new com.github.cerealklla.settlemynts.plotsign.RequestHireNpcPayload(data.signPos()));
        onClose();
    }

    private void establishHighway() {
        send(new com.github.cerealklla.settlemynts.plotsign.RequestEstablishHighwayMapPayload(data.signPos()));
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
