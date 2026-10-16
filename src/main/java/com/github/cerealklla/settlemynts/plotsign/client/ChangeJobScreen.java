package com.github.cerealklla.settlemynts.plotsign.client;

import com.github.cerealklla.settlemynts.plotsign.OpenNpcMenuPayload;
import com.github.cerealklla.settlemynts.plotsign.SetPlotJobPayload;
import com.github.cerealklla.settlemynts.zone.PlotJob;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

/**
 * "Change Job" picker -- explicit user spec, 2026-10-16: "allows selecting Farmer or Lumberjack for
 * now." No server round trip needed just to open this (unlike {@code NpcMenuScreen}'s own options,
 * which depend on live plot/permission state) -- the three choices are always the same, so {@code
 * client.NpcMenuScreen}'s "Change Job" button opens this directly. Picking one sends {@link
 * SetPlotJobPayload}, which the server re-validates (owner + private-residence-plot) before applying
 * -- "visible but re-validated" discipline, same as everywhere else in this menu system.
 */
public final class ChangeJobScreen extends Screen {

    private final OpenNpcMenuPayload data;
    private final Screen parent;

    public ChangeJobScreen(OpenNpcMenuPayload data, Screen parent) {
        super(Component.literal("Change Job"));
        this.data = data;
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int y = height / 2 - 30;

        addRenderableWidget(Button.builder(Component.literal("Farmer"), b -> setJob(PlotJob.FARMER))
                .bounds(centerX - 100, y, 200, 20).build());
        y += 24;
        addRenderableWidget(Button.builder(Component.literal("Lumberjack"), b -> setJob(PlotJob.LUMBERJACK))
                .bounds(centerX - 100, y, 200, 20).build());
        y += 24;
        addRenderableWidget(Button.builder(Component.literal("Clear"), b -> setJob(PlotJob.NONE))
                .bounds(centerX - 100, y, 200, 20).build());
        y += 32;

        addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose())
                .bounds(centerX - 50, y, 100, 20).build());
    }

    private void setJob(PlotJob job) {
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(new SetPlotJobPayload(data.anchor(), job)));
        Minecraft.getInstance().setScreen(null);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
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
