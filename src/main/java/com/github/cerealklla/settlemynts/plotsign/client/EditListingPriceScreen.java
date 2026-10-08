package com.github.cerealklla.settlemynts.plotsign.client;

import com.github.cerealklla.settlemynts.plotsign.OpenShopPayload;
import com.github.cerealklla.settlemynts.plotsign.SetListingPricePayload;
import com.github.cerealklla.settlemynts.plotsign.ShopListingEntry;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

/**
 * "Edit Price" on the Manage Shop screen (2026-10-08, replacing the old +/-1/+/-10 click-spam) --
 * real user feedback: "why would you have a button to do that and not simply allow the player to
 * type the desired listing cost? What if they want to sell a T5 sword for 10,000 nuggets? You want
 * them to spam click +10 1000 times?" A single numeric {@link EditBox}, pre-filled with the
 * listing's current price. "Cancel" reopens {@code ShopScreen} directly from the same {@link
 * OpenShopPayload} this screen was given (nothing changed, so the held data is still accurate).
 * "Set Price" sends the change and closes outright instead -- reopening with that same now-stale
 * {@code shopData} would show the price it had *before* the edit, so this follows the same "action
 * then close, reopen the sign for fresh state" convention every other Manage action in {@code
 * ShopScreen} already uses.
 */
public final class EditListingPriceScreen extends Screen {

    private final OpenShopPayload shopData;
    private final ShopListingEntry listing;
    private EditBox priceBox;

    public EditListingPriceScreen(OpenShopPayload shopData, ShopListingEntry listing) {
        super(Component.literal("Set Price"));
        this.shopData = shopData;
        this.listing = listing;
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int y = height / 2 - 30;

        priceBox = addRenderableWidget(new EditBox(font, centerX - 60, y, 120, 20, Component.literal("Price")));
        priceBox.setMaxLength(9);
        priceBox.setFilter(s -> s.isEmpty() || s.chars().allMatch(Character::isDigit));
        priceBox.setValue(Integer.toString(listing.pricePerUnit()));
        setInitialFocus(priceBox);
        y += 30;

        addRenderableWidget(Button.builder(Component.literal("Set Price"), b -> confirm())
                .bounds(centerX - 100, y, 95, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> cancel())
                .bounds(centerX + 5, y, 95, 20).build());
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 257 || event.key() == 335) { // GLFW_KEY_ENTER / GLFW_KEY_KP_ENTER
            confirm();
            return true;
        }
        return super.keyPressed(event);
    }

    private void confirm() {
        int price;
        try {
            price = Integer.parseInt(priceBox.getValue());
        } catch (NumberFormatException e) {
            return;
        }
        if (price < 1) {
            return;
        }
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(
                new SetListingPricePayload(shopData.signPos(), listing.resourceKey(), listing.isTag(), price)));
        // Closes outright rather than reopening ShopScreen with this same (now-stale) shopData --
        // same "reopen the sign to see fresh state" convention every other Manage action in that
        // screen already follows (see its own class doc), avoiding a confusing "I set it to 10000 but
        // it still shows the old price" moment.
        Minecraft.getInstance().setScreen(null);
    }

    private void cancel() {
        Minecraft.getInstance().setScreen(new ShopScreen(shopData));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int centerX = width / 2;
        String label = listing.label();
        graphics.text(font, label, centerX - font.width(label) / 2, height / 2 - 55, 0xFFFFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
