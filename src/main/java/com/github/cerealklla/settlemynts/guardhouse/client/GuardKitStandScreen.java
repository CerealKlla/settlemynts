package com.github.cerealklla.settlemynts.guardhouse.client;

import com.github.cerealklla.settlemynts.guardhouse.OpenGuardKitStandMenuPayload;
import com.github.cerealklla.settlemynts.guardhouse.PickUpGuardKitStandPayload;
import com.github.cerealklla.settlemynts.guardhouse.RequestCreateKytForStandPayload;
import com.github.cerealklla.settlemynts.guardhouse.RequestEditKytForStandPayload;
import com.github.cerealklla.settlemynts.guardhouse.RequestGuardKitStandKytNamesPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * The Guard Kit Stand's "Kit Menu" (Guard Kit Stand overhaul, 2026-10-16) -- opened via {@link
 * OpenGuardKitStandMenuPayload}. "Create Kyt" expands an inline name box + Confirm in place of the
 * main buttons, same shape as Kyt's own {@code structure.client.KytMainMenuScreen} -- the server
 * binds the name to this stand immediately and opens Kyt's real editor for it. "Edit Kyt" shows
 * instead once a Kyt is already bound. "Select Kyt" opens {@link SelectKytScreen} (an existing saved
 * Kyt picker). Both Create/Edit/Select are hidden entirely on a Kyt-less server ({@code
 * data.kytAvailable()}). The respawn-timer line is plain text, no interaction.
 */
public final class GuardKitStandScreen extends Screen {

    private final OpenGuardKitStandMenuPayload data;
    private boolean creating;
    private EditBox nameBox;

    public GuardKitStandScreen(OpenGuardKitStandMenuPayload data) {
        super(Component.literal("Guard Kit Stand"));
        this.data = data;
    }

    public OpenGuardKitStandMenuPayload data() {
        return data;
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int y = 55;

        if (creating) {
            nameBox = addRenderableWidget(new EditBox(font, centerX - 100, y, 200, 20, Component.literal("Kyt name")));
            nameBox.setMaxLength(48);
            y += 24;
            addRenderableWidget(Button.builder(Component.literal("Confirm"), b -> confirmCreate())
                    .bounds(centerX - 100, y, 95, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> {
                creating = false;
                rebuildWidgets();
            }).bounds(centerX + 5, y, 95, 20).build());
            y += 30;
        } else if (data.kytAvailable()) {
            if (data.boundKytName().isPresent()) {
                addRenderableWidget(Button.builder(Component.literal("Edit Kyt"), b -> send(new RequestEditKytForStandPayload(data.standId())))
                        .bounds(centerX - 100, y, 200, 20).build());
            } else {
                addRenderableWidget(Button.builder(Component.literal("Create Kyt"), b -> {
                    creating = true;
                    rebuildWidgets();
                }).bounds(centerX - 100, y, 200, 20).build());
            }
            y += 30;

            addRenderableWidget(Button.builder(Component.literal("Select Kyt"), b -> send(new RequestGuardKitStandKytNamesPayload(data.standId())))
                    .bounds(centerX - 100, y, 200, 20).build());
            y += 30;
        }

        addRenderableWidget(Button.builder(Component.literal("Pick Up"), b -> pickUp())
                .bounds(centerX - 100, y, 200, 20).build());
        y += 40;

        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(centerX - 50, y, 100, 20).build());
    }

    private void confirmCreate() {
        String name = nameBox.getValue().trim();
        if (name.isEmpty()) {
            return;
        }
        send(new RequestCreateKytForStandPayload(data.standId(), name));
    }

    private void pickUp() {
        send(new PickUpGuardKitStandPayload(data.standId()));
        onClose();
    }

    private void send(CustomPacketPayload payload) {
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(payload));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int titleWidth = font.width(title);
        graphics.text(font, title, width / 2 - titleWidth / 2, 20, 0xFFFFFFFF);

        String kytLine = "Kyt: " + data.boundKytName().orElse("(none)");
        graphics.text(font, kytLine, width / 2 - font.width(kytLine) / 2, 36, 0xFFAAAAAA);

        String statusLine = data.guardAlive()
                ? "Guard active"
                : data.secondsUntilRespawn() <= 0 ? "Ready to spawn" : "Respawning in " + data.secondsUntilRespawn() + "s";
        graphics.text(font, statusLine, width / 2 - font.width(statusLine) / 2, 46, 0xFF55FF55);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
