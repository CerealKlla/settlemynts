package com.github.cerealklla.settlemynts.plotsign.client;

import java.util.List;

import com.github.cerealklla.settlemynts.plotsign.AcceptOrTurnInQuestPayload;
import com.github.cerealklla.settlemynts.plotsign.OpenShopWishlistPayload;
import com.github.cerealklla.settlemynts.plotsign.QuestOfferInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

/**
 * "I've got a job that needs doing" -- {@code client.ChatMenuScreen}'s own quest sub-screen
 * (2026-10-10, explicit user spec). Shows the quest's name and a blurb, plus an action button
 * when there's actually something to do right now ({@link QuestOfferInfo.QuestAction#ACCEPT} /
 * {@link QuestOfferInfo.QuestAction#TURN_IN}) -- {@link QuestOfferInfo.QuestAction#NONE} just
 * shows the blurb with no button, matching {@code quest.QuestTurnIn}'s own "still working on it"
 * case. Clicking the action button sends {@link AcceptOrTurnInQuestPayload}; the server's reply (a
 * fresh {@link OpenShopWishlistPayload}) lands back on {@link ChatMenuScreen}, not here -- see
 * {@code SettlemyntsModClient}'s own dispatch doc.
 *
 * <p>The blurb is word-wrapped and the panel sized to actually fit it (2026-10-10, real live-test
 * report -- a longer blurb like "Night Raiders" overran the fixed-size panel entirely, spilling
 * off both edges of the screen and overlapping the Active Quest HUD).
 */
public final class QuestOfferScreen extends Screen {

    private static final int PANEL_WIDTH = 360;
    private static final int TEXT_WIDTH = PANEL_WIDTH - 40;

    private final OpenShopWishlistPayload data;
    private final QuestOfferInfo info;

    private List<FormattedCharSequence> blurbLines;
    private int panelTop;
    private int panelBottom;
    private int nameY;
    private int blurbTop;

    public QuestOfferScreen(OpenShopWishlistPayload data) {
        super(Component.literal("..."));
        this.data = data;
        this.info = data.questOffer().orElseThrow();
    }

    @Override
    protected void init() {
        blurbLines = font.split(Component.literal(info.blurb()), TEXT_WIDTH);
        int blurbHeight = blurbLines.size() * font.lineHeight;

        String buttonLabel = switch (info.action()) {
            case ACCEPT -> "Accept Quest";
            case TURN_IN -> "Turn In";
            case NONE -> null;
        };

        // Layout, top to bottom: title, gap, wrapped blurb, gap, (action button + gap), Back button, padding.
        int centerX = width / 2;
        int contentHeight = 16 + 10 + blurbHeight + 14 + (buttonLabel != null ? 26 : 0) + 20;
        panelTop = height / 2 - contentHeight / 2 - 10;
        panelBottom = panelTop + contentHeight + 20;

        nameY = panelTop + 14;
        blurbTop = nameY + 16;
        int y = blurbTop + blurbHeight + 14;

        if (buttonLabel != null) {
            addRenderableWidget(Button.builder(Component.literal(buttonLabel), b -> accept())
                    .bounds(centerX - 100, y, 200, 20).build());
            y += 26;
        }

        addRenderableWidget(Button.builder(Component.literal("Back"), b -> Minecraft.getInstance().setScreen(new ChatMenuScreen(data)))
                .bounds(centerX - 50, y, 100, 20).build());
    }

    private void accept() {
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(new AcceptOrTurnInQuestPayload(data.anchor())));
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
        graphics.text(font, info.questName(), centerX - font.width(info.questName()) / 2, nameY, 0xFFFFD700);

        int y = blurbTop;
        for (FormattedCharSequence line : blurbLines) {
            graphics.text(font, line, centerX - font.width(line) / 2, y, 0xFFFFFFFF);
            y += font.lineHeight;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
