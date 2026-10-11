package com.github.cerealklla.settlemynts.plotsign.client;

import java.util.List;

import com.github.cerealklla.settlemynts.plotsign.AcceptBoardOfferPayload;
import com.github.cerealklla.settlemynts.plotsign.OpenShopWishlistPayload;
import com.github.cerealklla.settlemynts.plotsign.QuestMenuInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.util.FormattedCharSequence;

/**
 * {@code client.QuestListScreen}'s own sub-screen for the plot's board offer -- shows the full
 * flavor description and an "Accept Quest" button sending {@link AcceptBoardOfferPayload}. The
 * description is word-wrapped and the panel sized to fit it, same fix (and same reason) as {@code
 * client.QuestCompleteScreen}'s sibling screens -- a longer quest's blurb used to spill off the
 * screen entirely before this.
 */
public final class QuestAcceptScreen extends Screen {

    private static final int PANEL_WIDTH = 360;
    private static final int TEXT_WIDTH = PANEL_WIDTH - 40;

    private final OpenShopWishlistPayload data;
    private final QuestMenuInfo.NewOffer offer;

    private List<FormattedCharSequence> descriptionLines;
    private int panelTop;
    private int panelBottom;
    private int nameY;
    private int descriptionTop;

    public QuestAcceptScreen(OpenShopWishlistPayload data, QuestMenuInfo.NewOffer offer) {
        super(Component.literal("..."));
        this.data = data;
        this.offer = offer;
    }

    @Override
    protected void init() {
        descriptionLines = font.split(Component.literal(offer.description()), TEXT_WIDTH);
        int descriptionHeight = descriptionLines.size() * font.lineHeight;

        int centerX = width / 2;
        int contentHeight = 16 + 10 + descriptionHeight + 14 + 26 + 20;
        panelTop = height / 2 - contentHeight / 2 - 10;
        panelBottom = panelTop + contentHeight + 20;

        nameY = panelTop + 14;
        descriptionTop = nameY + 16;
        int y = descriptionTop + descriptionHeight + 14;

        addRenderableWidget(Button.builder(Component.literal("Accept Quest"), b -> accept())
                .bounds(centerX - 100, y, 200, 20).build());
        y += 26;
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> Minecraft.getInstance().setScreen(new QuestListScreen(data)))
                .bounds(centerX - 50, y, 100, 20).build());
    }

    private void accept() {
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(new AcceptBoardOfferPayload(data.anchor(), offer.questId())));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int centerX = width / 2;
        graphics.fill(centerX - PANEL_WIDTH / 2, panelTop, centerX + PANEL_WIDTH / 2, panelBottom, 0xC0101010);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int centerX = width / 2;
        graphics.text(font, offer.questName(), centerX - font.width(offer.questName()) / 2, nameY, 0xFFFFD700);

        int y = descriptionTop;
        for (FormattedCharSequence line : descriptionLines) {
            graphics.text(font, line, centerX - font.width(line) / 2, y, 0xFFFFFFFF);
            y += font.lineHeight;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
