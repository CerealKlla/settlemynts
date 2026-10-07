package com.github.cerealklla.settlemynts.founding.client;

import com.github.cerealklla.settlemynts.founding.RepositionTownHallCorePayload;
import com.github.cerealklla.settlemynts.zone.RequestPlotStakePayload;
import com.github.cerealklla.settlemynts.zone.SetShowPlotPerimetersPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

/**
 * Plot-management submenu, split out of {@link FoundingScreen} (added 2026-10-06, explicit user
 * request: "the Town Hall Core UI now has too many items in it") -- "Get Plot Stakes," the "Show
 * Plot Perimeters" toggle, and "Reposition Town Hall Core" (shown only when the server already
 * confirmed a qualifying Town Hall plot exists). Only ever opened from a finalized settlement's
 * {@code FoundingScreen}, which is why none of these buttons need their own pre-finalize gating
 * here -- the parent screen already only shows the "Plots..." button once finalized.
 */
public final class PlotManagementScreen extends Screen {

    private final FoundingScreen parent;
    private final int coreEntityId;
    private final boolean hasTownHallPlot;
    private boolean showPlotPerimeters;

    private Button plotPerimetersToggleButton;

    public PlotManagementScreen(FoundingScreen parent, int coreEntityId, boolean showPlotPerimeters, boolean hasTownHallPlot) {
        super(Component.literal("Plots"));
        this.parent = parent;
        this.coreEntityId = coreEntityId;
        this.showPlotPerimeters = showPlotPerimeters;
        this.hasTownHallPlot = hasTownHallPlot;
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int y = 40;

        addRenderableWidget(Button.builder(Component.literal("Get Plot Stakes"), b -> getPlotStake())
                .bounds(centerX - 100, y, 245, 20).build());
        y += 30;

        plotPerimetersToggleButton = addRenderableWidget(Button.builder(plotPerimetersToggleLabel(), b -> togglePlotPerimeters())
                .bounds(centerX - 100, y, 245, 20).build());
        y += 30;

        if (hasTownHallPlot) {
            addRenderableWidget(Button.builder(Component.literal("Reposition Town Hall Core"), b -> repositionTownHallCore())
                    .bounds(centerX - 100, y, 245, 20).build());
            y += 30;
        }

        addRenderableWidget(Button.builder(Component.literal("Back"), b -> Minecraft.getInstance().setScreen(parent))
                .bounds(centerX - 50, height - 30, 100, 20).build());
    }

    private void getPlotStake() {
        send(new RequestPlotStakePayload(coreEntityId));
    }

    private void togglePlotPerimeters() {
        showPlotPerimeters = !showPlotPerimeters;
        send(new SetShowPlotPerimetersPayload(coreEntityId, showPlotPerimeters));
        plotPerimetersToggleButton.setMessage(plotPerimetersToggleLabel());
        parent.updateShowPlotPerimeters(showPlotPerimeters);
    }

    private Component plotPerimetersToggleLabel() {
        return Component.literal("Show Plot Perimeters: " + (showPlotPerimeters ? "ON" : "OFF"));
    }

    private void repositionTownHallCore() {
        send(new RepositionTownHallCorePayload(coreEntityId));
        onClose();
    }

    private void send(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(payload));
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int titleWidth = font.width(title);
        graphics.text(font, title, width / 2 - titleWidth / 2, 15, 0xFFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
