package com.github.cerealklla.settlemynts.zone.client;

import java.util.List;

import com.github.cerealklla.settlemynts.api.Settlemynts;
import com.github.cerealklla.settlemynts.zone.FinalizePlotPayload;
import com.github.cerealklla.settlemynts.zone.OpenPlotStakeScreenPayload;
import com.github.cerealklla.settlemynts.zone.RemovePlotStakePayload;
import com.github.cerealklla.settlemynts.zone.RequestRoadAccessFlagPayload;
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
    private final boolean valid;
    private final boolean hasRoadAccessFlag;
    private final List<ZoneType> zoneTypes;
    private int selectedZoneTypeIndex;

    private EditBox nameBox;
    private EditBox ownerBox;
    private Button zoneTypeButton;
    private Button finalizeButton;

    public PlotStakeScreen(OpenPlotStakeScreenPayload payload) {
        super(Component.literal("Plot"));
        this.stakeEntityId = payload.stakeEntityId();
        this.stakeCount = payload.stakeCount();
        this.valid = payload.valid();
        this.hasRoadAccessFlag = payload.hasRoadAccessFlag();
        this.zoneTypes = List.copyOf(Settlemynts.getRegisteredZoneTypes());
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int y = 40;

        nameBox = addRenderableWidget(new EditBox(font, centerX - 100, y, 200, 20, Component.literal("Plot name")));
        nameBox.setMaxLength(64);
        y += 30;

        // Design doc Section 10: "Owner -- a player name, or an NPC (defaults to NPC)." Left blank,
        // this plot stays NPC-owned -- a real, resolvable player name is only ever required if the
        // planner wants to assign one now (added 2026-09-30).
        ownerBox = addRenderableWidget(new EditBox(font, centerX - 100, y, 200, 20, Component.literal("Owner (blank = NPC)")));
        ownerBox.setMaxLength(16);
        ownerBox.setHint(Component.literal("Owner (blank = NPC)"));
        y += 30;

        if (!zoneTypes.isEmpty()) {
            zoneTypeButton = addRenderableWidget(Button.builder(zoneTypeLabel(), b -> cycleZoneType())
                    .bounds(centerX - 100, y, 200, 20).build());
            y += 30;
        }
        updateOwnerBoxForSelectedType();

        addRenderableWidget(Button.builder(
                Component.literal(hasRoadAccessFlag ? "Get Road Access Flag (replace)" : "Get Road Access Flag"),
                b -> requestRoadAccessFlag()).bounds(centerX - 100, y, 200, 20).build());
        y += 30;

        finalizeButton = addRenderableWidget(Button.builder(Component.literal("Finalize Plot (" + stakeCount + " stakes)"), b -> finalizePlot())
                .bounds(centerX - 100, y, 200, 20).build());
        finalizeButton.active = valid && hasRoadAccessFlag;
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
        updateOwnerBoxForSelectedType();
    }

    /** Design doc Section 14a's Guardhouse ("can only ever be NPC-owned") -- the server enforces this
     * regardless (see {@code SettlemyntsMod#finalizePlot}), but disabling/clearing the Owner field
     * here avoids a confusing "I typed a name and it still said NPC-owned" surprise. */
    private void updateOwnerBoxForSelectedType() {
        boolean npcOwnedOnly = !zoneTypes.isEmpty() && zoneTypes.get(selectedZoneTypeIndex).npcOwnedOnly();
        ownerBox.setEditable(!npcOwnedOnly);
        if (npcOwnedOnly) {
            ownerBox.setValue("");
            ownerBox.setHint(Component.literal("NPC-owned only"));
        } else {
            ownerBox.setHint(Component.literal("Owner (blank = NPC)"));
        }
    }

    private void finalizePlot() {
        if (zoneTypes.isEmpty() || nameBox.getValue().isBlank() || !valid || !hasRoadAccessFlag) {
            return;
        }
        Identifier zoneTypeId = zoneTypes.get(selectedZoneTypeIndex).id();
        send(new FinalizePlotPayload(stakeEntityId, nameBox.getValue(), zoneTypeId.toString(), ownerBox.getValue()));
        onClose();
    }

    private void removeStake() {
        send(new RemovePlotStakePayload(stakeEntityId));
        onClose();
    }

    private void requestRoadAccessFlag() {
        send(new RequestRoadAccessFlagPayload(stakeEntityId));
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
        } else if (!valid) {
            graphics.text(font, "No 15x15 buildable area yet -- add or adjust stakes.", width / 2 - 100, 25, 0xFF5555);
        } else if (!hasRoadAccessFlag) {
            graphics.text(font, "Place the Road Access Flag on the perimeter to finalize.", width / 2 - 100, 25, 0xFF5555);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
