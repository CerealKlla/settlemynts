package com.github.cerealklla.settlemynts.tavern.client;

import java.util.List;

import com.github.cerealklla.settlemynts.tavern.OpenTavernHireMenuPayload;
import com.github.cerealklla.settlemynts.tavern.TavernHireAcceptPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.util.FormattedCharSequence;

/**
 * "Talk" prompt for a seated Tavern NPC -- 2026-10-31 correction, explicit user spec: right-clicking
 * used to instantly attempt payment or silently start a Trial; this shows what the NPC wants first
 * and only acts once the player clicks the action button, sending {@link TavernHireAcceptPayload}.
 * Same panel/word-wrap shape as {@code plotsign.client.QuestAcceptScreen} -- the established
 * "polished" accept-prompt convention in this mod, reused rather than inventing a new look.
 */
public final class TavernHireScreen extends Screen {

    private static final int PANEL_WIDTH = 360;
    private static final int TEXT_WIDTH = PANEL_WIDTH - 40;

    private final OpenTavernHireMenuPayload data;

    private List<FormattedCharSequence> descriptionLines;
    private int panelTop;
    private int panelBottom;
    private int nameY;
    private int descriptionTop;

    public TavernHireScreen(OpenTavernHireMenuPayload data) {
        super(Component.literal("..."));
        this.data = data;
    }

    @Override
    protected void init() {
        descriptionLines = font.split(Component.literal(data.description()), TEXT_WIDTH);
        int descriptionHeight = descriptionLines.size() * font.lineHeight;

        int centerX = width / 2;
        int contentHeight = 16 + 10 + descriptionHeight + 14 + 26 + 20;
        panelTop = height / 2 - contentHeight / 2 - 10;
        panelBottom = panelTop + contentHeight + 20;

        nameY = panelTop + 14;
        descriptionTop = nameY + 16;
        int y = descriptionTop + descriptionHeight + 14;

        boolean trialBlocked = data.isTrial() && data.alreadyActiveTrial();
        Component buttonLabel = Component.literal(
                trialBlocked ? "Trial already in progress" : data.isTrial() ? "Start Trial" : "Pay");
        Button actionButton = Button.builder(buttonLabel, b -> accept())
                .bounds(centerX - 100, y, 200, 20).build();
        actionButton.active = !trialBlocked;
        addRenderableWidget(actionButton);
        y += 26;
        addRenderableWidget(Button.builder(Component.literal("Nevermind"), b -> onClose())
                .bounds(centerX - 50, y, 100, 20).build());
    }

    private void accept() {
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(new TavernHireAcceptPayload(data.npcEntityId())));
        onClose();
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
        graphics.text(font, data.npcName(), centerX - font.width(data.npcName()) / 2, nameY, 0xFFFFD700);

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
