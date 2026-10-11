package com.github.cerealklla.settlemynts.plotsign.client;

import com.github.cerealklla.settlemynts.plotsign.CompleteQuestPayload;
import com.github.cerealklla.settlemynts.plotsign.OpenShopWishlistPayload;
import com.github.cerealklla.settlemynts.plotsign.QuestMenuInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

/**
 * {@code client.QuestListScreen}'s own per-instance sub-menu (2026-10-10, explicit user spec: "a
 * sub-menu [with] the option to complete the quest [that] checks if all required milestones are
 * complete"). "Complete Quest" sends {@link CompleteQuestPayload}; the server replies with a fresh
 * {@link OpenShopWishlistPayload} that always lands back on {@link ChatMenuScreen}, not here -- see
 * {@code SettlemyntsModClient}'s own dispatch doc. A failed attempt (still missing a kill-count
 * milestone, say) is reported back as a real chat message by {@code quest.QuestTurnIn}'s own
 * server-side logic, not anything client-predicted here.
 */
public final class QuestCompleteScreen extends Screen {

    private final OpenShopWishlistPayload data;
    private final QuestMenuInfo.ActiveEntry entry;

    public QuestCompleteScreen(OpenShopWishlistPayload data, QuestMenuInfo.ActiveEntry entry) {
        super(Component.literal("..."));
        this.data = data;
        this.entry = entry;
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int centerY = height / 2;

        addRenderableWidget(Button.builder(Component.literal("Complete Quest"), b -> complete())
                .bounds(centerX - 100, centerY + 10, 200, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> Minecraft.getInstance().setScreen(new QuestListScreen(data)))
                .bounds(centerX - 50, centerY + 36, 100, 20).build());
    }

    private void complete() {
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(new CompleteQuestPayload(data.anchor(), entry.instanceId())));
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
        graphics.text(font, entry.questName(), centerX - font.width(entry.questName()) / 2, centerY - 30, 0xFFFFD700);
        String status = entry.readyToComplete() ? "Everything's ready -- bring it in." : "Still working on it.";
        graphics.text(font, status, centerX - font.width(status) / 2, centerY - 14, 0xFFFFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
