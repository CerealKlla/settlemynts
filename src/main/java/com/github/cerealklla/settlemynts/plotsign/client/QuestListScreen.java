package com.github.cerealklla.settlemynts.plotsign.client;

import com.github.cerealklla.settlemynts.plotsign.OpenShopWishlistPayload;
import com.github.cerealklla.settlemynts.plotsign.QuestMenuInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * "I've got a job that needs doing" -- {@code client.ChatMenuScreen}'s own quest picker (2026-10-10,
 * explicit user spec: "you should be able to select your active quest by name, and then in a
 * sub-menu have the option to complete the quest"). One button per the player's own active quests
 * from this NPC's pool (by name, their hidden instanceId never shown -- see {@code
 * QuestMenuInfo.ActiveEntry}'s own doc), plus one more for the plot's board offer if there is one.
 * Clicking either opens its own sub-screen ({@link QuestCompleteScreen}/{@link QuestAcceptScreen}),
 * same no-extra-round-trip shape as every other screen transition here.
 */
public final class QuestListScreen extends Screen {

    private final OpenShopWishlistPayload data;

    public QuestListScreen(OpenShopWishlistPayload data) {
        super(Component.literal("..."));
        this.data = data;
    }

    @Override
    protected void init() {
        QuestMenuInfo menu = data.questMenu();
        int centerX = width / 2;
        int rowCount = menu.activeQuests().size() + (menu.newOffer().isPresent() ? 1 : 0);
        int top = height / 2 - (rowCount * 24 + 30) / 2;
        int y = top;

        for (QuestMenuInfo.ActiveEntry entry : menu.activeQuests()) {
            addRenderableWidget(Button.builder(Component.literal(entry.questName()),
                            b -> Minecraft.getInstance().setScreen(new QuestCompleteScreen(data, entry)))
                    .bounds(centerX - 110, y, 220, 20).build());
            y += 24;
        }
        int offerY = y;
        menu.newOffer().ifPresent(offer -> addRenderableWidget(Button.builder(Component.literal("New: " + offer.questName()),
                        b -> Minecraft.getInstance().setScreen(new QuestAcceptScreen(data, offer)))
                .bounds(centerX - 110, offerY, 220, 20).build()));
        if (menu.newOffer().isPresent()) {
            y += 24;
        }

        addRenderableWidget(Button.builder(Component.literal("Back"), b -> Minecraft.getInstance().setScreen(new ChatMenuScreen(data)))
                .bounds(centerX - 50, y + 10, 100, 20).build());
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        QuestMenuInfo menu = data.questMenu();
        int rowCount = menu.activeQuests().size() + (menu.newOffer().isPresent() ? 1 : 0);
        int centerX = width / 2;
        int centerY = height / 2;
        int halfHeight = (rowCount * 24 + 50) / 2;
        graphics.fill(centerX - 130, centerY - halfHeight, centerX + 130, centerY + halfHeight, 0xC0101010);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
