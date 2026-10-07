package com.github.cerealklla.settlemynts.founding.client;

import com.github.cerealklla.settlemynts.founding.OpenStakeScreenPayload;
import com.github.cerealklla.settlemynts.founding.RemoveStakePayload;
import com.github.cerealklla.settlemynts.founding.SetStakeAbsolutePayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * A Planned Perimeter Stake's management UI (design doc Section 6) -- opened via {@link
 * OpenStakeScreenPayload}. Toggles "absolute" (capped at {@code maxAbsolute}, shown as "current/max"
 * and disabled at the cap -- design doc originally said "5/5", the cap is now {@link
 * GhostPerimeterStakeEntity#MAX_ABSOLUTE_STAKES} instead of a hardcoded number) or removes the stake
 * outright.
 */
public final class StakeScreen extends Screen {

    private final int stakeEntityId;
    private final boolean absolute;
    private final int absoluteCount;
    private final int maxAbsolute;

    public StakeScreen(OpenStakeScreenPayload payload) {
        super(Component.literal("Perimeter Stake"));
        this.stakeEntityId = payload.stakeEntityId();
        this.absolute = payload.absolute();
        this.absoluteCount = payload.absoluteCount();
        this.maxAbsolute = payload.maxAbsolute();
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int y = 50;

        String toggleLabel = (absolute ? "Absolute: ON" : "Absolute: OFF") + " (" + absoluteCount + "/" + maxAbsolute + ")";
        Button toggle = addRenderableWidget(Button.builder(Component.literal(toggleLabel), b -> toggleAbsolute())
                .bounds(centerX - 100, y, 200, 20).build());
        // Disabled only when trying to turn ON at the cap -- turning OFF (freeing a slot) is always allowed.
        toggle.active = absolute || absoluteCount < maxAbsolute;
        y += 30;

        addRenderableWidget(Button.builder(Component.literal("Remove Stake"), b -> remove())
                .bounds(centerX - 100, y, 200, 20).build());
        y += 40;

        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(centerX - 50, y, 100, 20).build());
    }

    private void toggleAbsolute() {
        send(new SetStakeAbsolutePayload(stakeEntityId, !absolute));
        onClose();
    }

    private void remove() {
        send(new RemoveStakePayload(stakeEntityId));
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
