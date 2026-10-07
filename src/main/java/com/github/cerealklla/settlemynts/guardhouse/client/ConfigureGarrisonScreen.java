package com.github.cerealklla.settlemynts.guardhouse.client;

import java.util.ArrayList;
import java.util.List;

import com.github.cerealklla.settlemynts.guardhouse.GarrisonSlot;
import com.github.cerealklla.settlemynts.guardhouse.GuardhouseConstants;
import com.github.cerealklla.settlemynts.guardhouse.OpenConfigureGarrisonPayload;
import com.github.cerealklla.settlemynts.guardhouse.RequestGarrisonKytNamesPayload;
import com.github.cerealklla.settlemynts.guardhouse.SetGarrisonSlotPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * "Configure Garrison" (design doc Section 14a, Guardhouse Plot Type) -- opened via {@link
 * OpenConfigureGarrisonPayload} from the Plot Config Sign's own Main Menu. Shows one row per slot
 * up to the current Tier's own capacity, each with a "Max Gear Tier" cycle button and an "Allow
 * Neighbor Purchase" toggle -- every click sends {@link SetGarrisonSlotPayload} immediately (no
 * separate "Save" step, same convention as {@code SetShowPlotPerimetersPayload}) and updates this
 * screen's own local copy optimistically, matching {@code zone.client.PlotStakeScreen#cycleZoneType}'s
 * client-local-then-commit shape.
 */
public final class ConfigureGarrisonScreen extends Screen {

    private final OpenConfigureGarrisonPayload data;
    private final List<GarrisonSlot> slots;
    private final Button[] gearTierButtons;
    private final Button[] neighborButtons;
    private final Button[] kytButtons;

    public ConfigureGarrisonScreen(OpenConfigureGarrisonPayload data) {
        super(Component.literal("Configure Garrison"));
        this.data = data;
        this.slots = new ArrayList<>(data.slots());
        this.gearTierButtons = new Button[data.capacity()];
        this.neighborButtons = new Button[data.capacity()];
        this.kytButtons = new Button[data.capacity()];
    }

    /** Re-exposed for {@link SelectKytScreen} -- after a pick, it builds an updated copy of this payload's slot list and reopens this screen with it, rather than needing a full server round trip just to redraw. */
    public OpenConfigureGarrisonPayload data() {
        return data;
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int y = 50;

        if (data.tier() <= 0) {
            addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose())
                    .bounds(centerX - 50, y + 20, 100, 20).build());
            return;
        }

        // Column widths/gap sized so the whole row (with or without the Kyt column, depending on
        // whether Kyt is even loaded) is centered on centerX instead of anchored from a fixed
        // left edge -- the previous fixed-offset layout ran the Kyt column off the right edge of
        // the screen on the first live test once it was added as a 4th column.
        int slotWidth = 55;
        int gearWidth = 120;
        int neighborWidth = 140;
        int kytWidth = 120;
        int gap = 6;

        int rowWidth = slotWidth + gearWidth + neighborWidth + gap * 2;
        if (data.kytAvailable()) {
            rowWidth += kytWidth + gap;
        }
        int rowX = centerX - rowWidth / 2;

        for (int i = 0; i < data.capacity(); i++) {
            int slotIndex = i;
            int x = rowX;

            addRenderableWidget(Button.builder(Component.literal("Slot " + (i + 1)), b -> {
            }).bounds(x, y, slotWidth, 20).build()).active = false;
            x += slotWidth + gap;

            gearTierButtons[i] = addRenderableWidget(Button.builder(gearTierLabel(slotIndex),
                    b -> cycleGearTier(slotIndex)).bounds(x, y, gearWidth, 20).build());
            x += gearWidth + gap;

            neighborButtons[i] = addRenderableWidget(Button.builder(neighborLabel(slotIndex),
                    b -> toggleNeighbor(slotIndex)).bounds(x, y, neighborWidth, 20).build());
            x += neighborWidth + gap;

            if (data.kytAvailable()) {
                kytButtons[i] = addRenderableWidget(Button.builder(kytLabel(slotIndex),
                        b -> requestKyt(slotIndex)).bounds(x, y, kytWidth, 20).build());
            }

            y += 24;
        }

        y += 10;
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose())
                .bounds(centerX - 50, y, 100, 20).build());
    }

    private Component gearTierLabel(int slotIndex) {
        return Component.literal("Max Gear Tier: " + slots.get(slotIndex).maxGearTier());
    }

    private Component neighborLabel(int slotIndex) {
        return Component.literal("Neighbor Purchase: " + (slots.get(slotIndex).allowNeighborPurchase() ? "ON" : "OFF"));
    }

    private Component kytLabel(int slotIndex) {
        return Component.literal("Kyt: " + slots.get(slotIndex).kytLoadoutName().orElse("None"));
    }

    private void requestKyt(int slotIndex) {
        send(new RequestGarrisonKytNamesPayload(data.guardhousePos(), slotIndex));
    }

    private void cycleGearTier(int slotIndex) {
        GarrisonSlot current = slots.get(slotIndex);
        int cap = GuardhouseConstants.maxGearTierForTier(data.tier());
        int next = current.maxGearTier() >= cap ? 1 : current.maxGearTier() + 1;
        GarrisonSlot updated = new GarrisonSlot(next, current.allowNeighborPurchase());
        slots.set(slotIndex, updated);
        gearTierButtons[slotIndex].setMessage(gearTierLabel(slotIndex));
        send(new SetGarrisonSlotPayload(data.guardhousePos(), slotIndex, updated.maxGearTier(), updated.allowNeighborPurchase()));
    }

    private void toggleNeighbor(int slotIndex) {
        GarrisonSlot current = slots.get(slotIndex);
        GarrisonSlot updated = new GarrisonSlot(current.maxGearTier(), !current.allowNeighborPurchase());
        slots.set(slotIndex, updated);
        neighborButtons[slotIndex].setMessage(neighborLabel(slotIndex));
        send(new SetGarrisonSlotPayload(data.guardhousePos(), slotIndex, updated.maxGearTier(), updated.allowNeighborPurchase()));
    }

    private void send(CustomPacketPayload payload) {
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(payload));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int titleWidth = font.width(title);
        graphics.text(font, title, width / 2 - titleWidth / 2, 20, 0xFFFFFFFF);
        if (data.tier() <= 0) {
            String message = "Bind a Blueprint to this Guardhouse's Construction Box first.";
            graphics.text(font, message, width / 2 - font.width(message) / 2, 40, 0xFFFF5555);
        } else {
            String message = "Tier " + data.tier() + " -- " + data.capacity() + " garrison slot(s).";
            graphics.text(font, message, width / 2 - font.width(message) / 2, 36, 0xFFAAAAAA);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
