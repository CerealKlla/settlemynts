package com.github.cerealklla.settlemynts.plotsign.client;

import com.github.cerealklla.settlemynts.plotsign.OpenShopWishlistPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * "Press H to chat" lands here first (2026-10-10, explicit user spec) -- up to two optional
 * buttons, both independent of each other:
 * <ul>
 *   <li>"I need a few things" -- present whenever this NPC's shop has an unmet Planned Inventory
 *   deficit (see {@code zone.ShopWishlist}'s own doc); opens {@link WishlistScreen} with the data
 *   already fetched for this screen, no extra round trip.</li>
 *   <li>"I've got a job that needs doing" -- present whenever Myssions has at least one active
 *   quest or a board offer for this NPC's pool (see {@code bridge.MyssionsQuestBridge}'s own doc),
 *   OR this is a Town Hall NPC with at least one posted Establish Highway offer ({@code
 *   plotsign.QuestMenuInfo#roadQuestOffers}); opens {@link QuestListScreen} (same no-extra-round-trip
 *   shape), which lets the player pick a specific active quest by name to complete, accept the
 *   board's offer, or accept a Highway offer.</li>
 * </ul>
 * The floating "!" nameplate ({@code plotsign.PlotShopProximityTicker#updateWishlistNameplate}) is
 * driven by a separate, wishlist/board-offer-only check and does NOT reflect Highway offers at all
 * (a nameplate is shared by every viewer, and {@code SettlemyntsMod#anchorHasWishlist}'s own doc
 * explains why it deliberately can't represent any one player's personal business) -- don't read
 * the nameplate's presence/absence as a signal either way for whether a Highway offer exists.
 *
 * <p><b>Fixed 2026-10-16</b> -- real live report: a Mayor had 2 posted Highway offers at their own
 * Town Hall NPC, but "Press H" said "Nothing to talk about right now." {@code roadQuestOffers} was
 * added to the server-side payload 2026-10-13 but this screen's own {@code hasQuest}/empty-state
 * checks were never updated to look at it -- {@link QuestListScreen} (what the button opens) already
 * handled it correctly the whole time, so only this screen's gating was ever actually broken.
 */
public final class ChatMenuScreen extends Screen {

    private OpenShopWishlistPayload data;

    public ChatMenuScreen(OpenShopWishlistPayload data) {
        super(Component.literal("..."));
        this.data = data;
    }

    /** Called when a fresh {@code OpenShopWishlistPayload} arrives (e.g. after backing out of {@link QuestOfferScreen} having just accepted/turned in a quest) while this screen is already open. */
    public void refresh(OpenShopWishlistPayload data) {
        this.data = data;
        rebuild();
    }

    @Override
    protected void init() {
        rebuild();
    }

    private void rebuild() {
        clearWidgets();
        int centerX = width / 2;
        int centerY = height / 2;
        int y = centerY - 30;

        boolean hasWishlist = !data.entries().isEmpty();
        boolean hasQuest = !data.questMenu().activeQuests().isEmpty() || !data.questMenu().newOffers().isEmpty()
                || !data.questMenu().roadQuestOffers().isEmpty();

        if (hasWishlist) {
            addRenderableWidget(Button.builder(Component.literal("I need a few things"), b -> onNeedThings())
                    .bounds(centerX - 100, y, 200, 20).build());
            y += 24;
        }
        if (hasQuest) {
            addRenderableWidget(Button.builder(Component.literal("I've got a job that needs doing"), b -> onJob())
                    .bounds(centerX - 100, y, 200, 20).build());
            y += 24;
        }
        if (!hasWishlist && !hasQuest) {
            y += 24;
        }

        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(centerX - 50, y + 10, 100, 20).build());
    }

    private void onNeedThings() {
        Minecraft.getInstance().setScreen(new WishlistScreen(data));
    }

    private void onJob() {
        Minecraft.getInstance().setScreen(new QuestListScreen(data));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int centerX = width / 2;
        int centerY = height / 2;
        graphics.fill(centerX - 110, centerY - 40, centerX + 110, centerY + 50, 0xC0101010);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (data.entries().isEmpty() && data.questMenu().activeQuests().isEmpty() && data.questMenu().newOffers().isEmpty()
                && data.questMenu().roadQuestOffers().isEmpty()) {
            String message = "Nothing to talk about right now.";
            graphics.text(font, message, width / 2 - font.width(message) / 2, height / 2 - 40, 0xFFAAAAAA);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
