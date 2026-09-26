package com.github.cerealklla.settlemynts.founding.client;

import java.util.List;

import com.github.cerealklla.settlemynts.founding.GrantTownPlannerPayload;
import com.github.cerealklla.settlemynts.founding.OpenFoundingScreenPayload;
import com.github.cerealklla.settlemynts.founding.RequestPerimeterStakePayload;
import com.github.cerealklla.settlemynts.founding.SetSettlementNamePayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

/**
 * The Ghost Town Hall Core's permissions/naming UI (design doc Section 6) -- opened via {@link
 * OpenFoundingScreenPayload}. A deliberately minimal first pass: name entry, granting Town Planner
 * by online player name (founder-only, enforced server-side regardless of what this screen shows),
 * a plain list of current planners, and retrieving a Planned Perimeter Stake. No Finalize button
 * yet -- the perimeter fit algorithm and finalization effects (design doc Sections 7-8) are a
 * separate milestone.
 */
public final class FoundingScreen extends Screen {

    private final int coreEntityId;
    private final String initialName;
    private final List<String> townPlannerNames;
    private final boolean viewerIsFounder;

    private EditBox nameBox;
    private EditBox grantBox;

    public FoundingScreen(OpenFoundingScreenPayload payload) {
        super(Component.literal("Settlement"));
        this.coreEntityId = payload.coreEntityId();
        this.initialName = payload.settlementName();
        this.townPlannerNames = payload.townPlannerNames();
        this.viewerIsFounder = payload.viewerIsFounder();
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int y = 40;

        nameBox = addRenderableWidget(new EditBox(font, centerX - 100, y, 150, 20, Component.literal("Settlement name")));
        nameBox.setMaxLength(64);
        nameBox.setValue(initialName);
        addRenderableWidget(Button.builder(Component.literal("Set Name"), b -> setName())
                .bounds(centerX + 55, y, 90, 20).build());
        y += 30;

        addRenderableWidget(Button.builder(Component.literal("Get Perimeter Stake"), b -> getStake())
                .bounds(centerX - 100, y, 245, 20).build());
        y += 30;

        if (viewerIsFounder) {
            grantBox = addRenderableWidget(new EditBox(font, centerX - 100, y, 150, 20, Component.literal("Player name")));
            grantBox.setMaxLength(16);
            addRenderableWidget(Button.builder(Component.literal("Grant Planner"), b -> grantPlanner())
                    .bounds(centerX + 55, y, 90, 20).build());
            y += 30;
        }

        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(centerX - 50, height - 30, 100, 20).build());
    }

    private void setName() {
        send(new SetSettlementNamePayload(coreEntityId, nameBox.getValue()));
    }

    private void getStake() {
        send(new RequestPerimeterStakePayload(coreEntityId));
    }

    private void grantPlanner() {
        if (grantBox != null && !grantBox.getValue().isBlank()) {
            send(new GrantTownPlannerPayload(coreEntityId, grantBox.getValue()));
        }
    }

    private void send(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(payload));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int titleWidth = font.width(title);
        graphics.text(font, title, width / 2 - titleWidth / 2, 15, 0xFFFFFF);

        int listY = 130;
        String header = "Town Planners:";
        graphics.text(font, header, width / 2 - 100, listY, 0xAAAAAA);
        listY += 12;
        for (String name : townPlannerNames) {
            graphics.text(font, name, width / 2 - 100, listY, 0xFFFFFF);
            listY += 12;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
