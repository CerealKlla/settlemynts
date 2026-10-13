package com.github.cerealklla.settlemynts.plotsign.client;

import com.github.cerealklla.settlemynts.plotsign.AcceptRoadQuestOfferPayload;
import com.github.cerealklla.settlemynts.plotsign.OpenShopWishlistPayload;
import com.github.cerealklla.settlemynts.plotsign.QuestMenuInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

/**
 * {@code client.QuestListScreen}'s own sub-screen for a not-yet-accepted Establish Highway offer --
 * added 2026-10-13, the sibling of {@link QuestAcceptScreen} for {@link QuestMenuInfo.RoadQuestOffer}
 * instead of {@link QuestMenuInfo.NewOffer} (see {@code AcceptRoadQuestOfferPayload}'s own doc for
 * why this needs a different accept payload).
 */
public final class RoadQuestAcceptScreen extends Screen {

    private final OpenShopWishlistPayload data;
    private final QuestMenuInfo.RoadQuestOffer offer;

    public RoadQuestAcceptScreen(OpenShopWishlistPayload data, QuestMenuInfo.RoadQuestOffer offer) {
        super(Component.literal("..."));
        this.data = data;
        this.offer = offer;
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int centerY = height / 2;

        addRenderableWidget(Button.builder(Component.literal("Accept Quest"), b -> accept())
                .bounds(centerX - 100, centerY + 10, 200, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> Minecraft.getInstance().setScreen(new QuestListScreen(data)))
                .bounds(centerX - 50, centerY + 36, 100, 20).build());
    }

    private void accept() {
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(
                new AcceptRoadQuestOfferPayload(data.anchor(), offer.destinationPlaceId())));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int centerX = width / 2;
        int centerY = height / 2;
        graphics.fill(centerX - 130, centerY - 40, centerX + 130, centerY + 60, 0xC0101010);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int centerX = width / 2;
        int centerY = height / 2;
        String title = "Establish Highway";
        graphics.text(font, title, centerX - font.width(title) / 2, centerY - 28, 0xFFFFD700);
        String line = "Connect to " + offer.destinationName() + " by road.";
        graphics.text(font, line, centerX - font.width(line) / 2, centerY - 12, 0xFFFFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
