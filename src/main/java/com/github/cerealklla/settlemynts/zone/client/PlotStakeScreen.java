package com.github.cerealklla.settlemynts.zone.client;

import java.util.List;

import com.github.cerealklla.settlemynts.api.Settlemynts;
import com.github.cerealklla.settlemynts.zone.FinalizePlotPayload;
import com.github.cerealklla.settlemynts.zone.OpenPlotStakeScreenPayload;
import com.github.cerealklla.settlemynts.zone.RemovePlotStakePayload;
import com.github.cerealklla.settlemynts.zone.ZoneType;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * A Plot Placement Stake's management UI (design doc Section 11a) -- opened via {@link
 * OpenPlotStakeScreenPayload}. Names the plot, cycles through {@code ZoneTypeRegistry}'s
 * currently-registered types (read directly client-side -- see that payload's own doc for why
 * nothing needs to be sent over the wire for this), finalizes, or removes just this one stake.
 */
public final class PlotStakeScreen extends Screen {

    private final int stakeEntityId;
    private final int stakeCount;
    private final List<ZoneType> zoneTypes;
    private int selectedZoneTypeIndex;

    private EditBox nameBox;
    private Button zoneTypeButton;

    public PlotStakeScreen(OpenPlotStakeScreenPayload payload) {
        super(Component.literal("Plot"));
        this.stakeEntityId = payload.stakeEntityId();
        this.stakeCount = payload.stakeCount();
        this.zoneTypes = List.copyOf(Settlemynts.getRegisteredZoneTypes());
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int y = 40;

        nameBox = addRenderableWidget(new EditBox(font, centerX - 100, y, 200, 20, Component.literal("Plot name")));
        nameBox.setMaxLength(64);
        y += 30;

        if (!zoneTypes.isEmpty()) {
            zoneTypeButton = addRenderableWidget(Button.builder(zoneTypeLabel(), b -> cycleZoneType())
                    .bounds(centerX - 100, y, 200, 20).build());
            y += 30;
        }

        addRenderableWidget(Button.builder(Component.literal("Finalize Plot (" + stakeCount + " stakes)"), b -> finalizePlot())
                .bounds(centerX - 100, y, 200, 20).build());
        y += 30;

        addRenderableWidget(Button.builder(Component.literal("Remove This Stake"), b -> removeStake())
                .bounds(centerX - 100, y, 200, 20).build());
        y += 40;

        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(centerX - 50, y, 100, 20).build());
    }

    private Component zoneTypeLabel() {
        ZoneType selected = zoneTypes.get(selectedZoneTypeIndex);
        return Component.literal("Zone Type: " + selected.label());
    }

    private void cycleZoneType() {
        selectedZoneTypeIndex = (selectedZoneTypeIndex + 1) % zoneTypes.size();
        zoneTypeButton.setMessage(zoneTypeLabel());
    }

    private void finalizePlot() {
        if (zoneTypes.isEmpty() || nameBox.getValue().isBlank()) {
            return;
        }
        Identifier zoneTypeId = zoneTypes.get(selectedZoneTypeIndex).id();
        send(new FinalizePlotPayload(stakeEntityId, nameBox.getValue(), zoneTypeId.toString()));
        onClose();
    }

    private void removeStake() {
        send(new RemovePlotStakePayload(stakeEntityId));
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
        if (zoneTypes.isEmpty()) {
            graphics.text(font, "No zone types registered -- can't finalize yet.", width / 2 - 100, 25, 0xFF5555);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
