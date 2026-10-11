package com.github.cerealklla.settlemynts.plotsign.client;

import com.github.cerealklla.settlemynts.plotsign.AcceptOrTurnInQuestPayload;
import com.github.cerealklla.settlemynts.plotsign.OpenShopWishlistPayload;
import com.github.cerealklla.settlemynts.plotsign.QuestOfferInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

/**
 * "I've got a job that needs doing" -- {@code client.ChatMenuScreen}'s own quest sub-screen
 * (2026-10-10, explicit user spec). Shows the quest's name and a short blurb, plus an action
 * button when there's actually something to do right now ({@link QuestOfferInfo.QuestAction#ACCEPT}
 * / {@link QuestOfferInfo.QuestAction#TURN_IN}) -- {@link QuestOfferInfo.QuestAction#NONE} just
 * shows the blurb with no button, matching {@code quest.QuestTurnIn}'s own "still working on it"
 * case. Clicking the action button sends {@link AcceptOrTurnInQuestPayload}; the server's reply (a
 * fresh {@link OpenShopWishlistPayload}) lands back on {@link ChatMenuScreen}, not here -- see
 * {@code SettlemyntsModClient}'s own dispatch doc.
 */
public final class QuestOfferScreen extends Screen {

    private final OpenShopWishlistPayload data;
    private final QuestOfferInfo info;

    public QuestOfferScreen(OpenShopWishlistPayload data) {
        super(Component.literal("..."));
        this.data = data;
        this.info = data.questOffer().orElseThrow();
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int centerY = height / 2;

        String buttonLabel = switch (info.action()) {
            case ACCEPT -> "Accept Quest";
            case TURN_IN -> "Turn In";
            case NONE -> null;
        };
        if (buttonLabel != null) {
            addRenderableWidget(Button.builder(Component.literal(buttonLabel), b -> accept())
                    .bounds(centerX - 100, centerY + 10, 200, 20).build());
        }

        addRenderableWidget(Button.builder(Component.literal("Back"), b -> Minecraft.getInstance().setScreen(new ChatMenuScreen(data)))
                .bounds(centerX - 50, centerY + (buttonLabel != null ? 36 : 10), 100, 20).build());
    }

    private void accept() {
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(new AcceptOrTurnInQuestPayload(data.anchor())));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int centerX = width / 2;
        int centerY = height / 2;
        graphics.fill(centerX - 110, centerY - 40, centerX + 110, centerY + 65, 0xC0101010);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int centerX = width / 2;
        int centerY = height / 2;
        graphics.text(font, info.questName(), centerX - font.width(info.questName()) / 2, centerY - 30, 0xFFFFD700);
        graphics.text(font, info.blurb(), centerX - font.width(info.blurb()) / 2, centerY - 14, 0xFFFFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
