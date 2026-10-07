package com.github.cerealklla.settlemynts.plotsign.client;

import com.github.cerealklla.settlemynts.plotsign.RelocatePlotSignPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** The Plot Config Sign's "Configure Plot" submenu (design doc Section 14a) -- currently just "Relocate Plot Sign". */
public final class PlotConfigureScreen extends Screen {

    private final BlockPos signPos;

    public PlotConfigureScreen(BlockPos signPos) {
        super(Component.literal("Configure Plot"));
        this.signPos = signPos;
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int y = 60;

        addRenderableWidget(Button.builder(Component.literal("Relocate Plot Sign"), b -> relocate())
                .bounds(centerX - 100, y, 200, 20).build());
        y += 40;

        addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose())
                .bounds(centerX - 50, y, 100, 20).build());
    }

    private void relocate() {
        send(new RelocatePlotSignPayload(signPos));
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
