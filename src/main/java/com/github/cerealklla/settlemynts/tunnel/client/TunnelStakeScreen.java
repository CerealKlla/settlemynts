package com.github.cerealklla.settlemynts.tunnel.client;

import com.github.cerealklla.settlemynts.tunnel.FinalizeTunnelStakePayload;
import com.github.cerealklla.settlemynts.tunnel.OpenTunnelStakeScreenPayload;
import com.github.cerealklla.settlemynts.tunnel.RemoveTunnelStakePayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * A Tunnel Stake's management UI -- opened via {@link OpenTunnelStakeScreenPayload} whenever a
 * Town Planner right-clicks one without a Tunnel Stake in hand (see {@code
 * TunnelStakeEntity#interact}). "Finalize" is only offered once this stake is actually paired --
 * mirrors {@code founding.client.StakeScreen}'s own shape.
 */
public final class TunnelStakeScreen extends Screen {

    private final int stakeEntityId;
    private final boolean paired;

    public TunnelStakeScreen(OpenTunnelStakeScreenPayload payload) {
        super(Component.literal("Tunnel Stake"));
        this.stakeEntityId = payload.stakeEntityId();
        this.paired = payload.paired();
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int y = 55;

        String statusLabel = paired ? "Paired with another Tunnel Stake" : "Not yet paired -- place or right-click a second stake";
        addRenderableWidget(Button.builder(Component.literal(statusLabel), b -> {
        }).bounds(centerX - 120, y, 240, 20).build()).active = false;
        y += 30;

        Button finalizeButton = addRenderableWidget(Button.builder(Component.literal("Finalize Into Tunnel"), b -> finalizeTunnel())
                .bounds(centerX - 100, y, 200, 20).build());
        finalizeButton.active = paired;
        y += 30;

        addRenderableWidget(Button.builder(Component.literal("Remove Stake"), b -> remove())
                .bounds(centerX - 100, y, 200, 20).build());
        y += 40;

        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(centerX - 50, y, 100, 20).build());
    }

    private void finalizeTunnel() {
        send(new FinalizeTunnelStakePayload(stakeEntityId));
        onClose();
    }

    private void remove() {
        send(new RemoveTunnelStakePayload(stakeEntityId));
        onClose();
    }

    private void send(CustomPacketPayload payload) {
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(payload));
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
