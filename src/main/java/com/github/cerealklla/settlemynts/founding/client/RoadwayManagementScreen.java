package com.github.cerealklla.settlemynts.founding.client;

import com.github.cerealklla.settlemynts.roadway.RequestRoadwayStakePayload;
import com.github.cerealklla.settlemynts.roadway.SetShowRoadwayStakesPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

/**
 * Roadway-management submenu, split out of {@link FoundingScreen} same session as {@link
 * PlotManagementScreen} (added 2026-10-06, explicit user request to break up the Town Hall Core
 * UI) -- "Get Roadway Stake" and the "Show Roadway Stakes" toggle. Only ever opened from a
 * finalized settlement's {@code FoundingScreen}.
 */
public final class RoadwayManagementScreen extends Screen {

    private final FoundingScreen parent;
    private final int coreEntityId;
    private boolean showRoadwayStakes;

    private Button roadwayStakesToggleButton;

    public RoadwayManagementScreen(FoundingScreen parent, int coreEntityId, boolean showRoadwayStakes) {
        super(Component.literal("Roadways"));
        this.parent = parent;
        this.coreEntityId = coreEntityId;
        this.showRoadwayStakes = showRoadwayStakes;
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int y = 40;

        addRenderableWidget(Button.builder(Component.literal("Get Roadway Stake"), b -> getRoadwayStake())
                .bounds(centerX - 100, y, 245, 20).build());
        y += 30;

        roadwayStakesToggleButton = addRenderableWidget(Button.builder(roadwayStakesToggleLabel(), b -> toggleRoadwayStakes())
                .bounds(centerX - 100, y, 245, 20).build());
        y += 30;

        addRenderableWidget(Button.builder(Component.literal("Back"), b -> Minecraft.getInstance().setScreen(parent))
                .bounds(centerX - 50, height - 30, 100, 20).build());
    }

    private void getRoadwayStake() {
        send(new RequestRoadwayStakePayload(coreEntityId));
    }

    private void toggleRoadwayStakes() {
        showRoadwayStakes = !showRoadwayStakes;
        send(new SetShowRoadwayStakesPayload(coreEntityId, showRoadwayStakes));
        roadwayStakesToggleButton.setMessage(roadwayStakesToggleLabel());
        parent.updateShowRoadwayStakes(showRoadwayStakes);
    }

    private Component roadwayStakesToggleLabel() {
        return Component.literal("Show Roadway Stakes: " + (showRoadwayStakes ? "ON" : "OFF"));
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
