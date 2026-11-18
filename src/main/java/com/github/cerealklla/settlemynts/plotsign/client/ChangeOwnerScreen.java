package com.github.cerealklla.settlemynts.plotsign.client;

import java.util.ArrayList;
import java.util.List;

import com.github.cerealklla.settlemynts.plotsign.OpenChangeOwnerScreenPayload;
import com.github.cerealklla.settlemynts.plotsign.SetPlotOwnerFromSignPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * "Change Owner" -- opened from {@code ConfigureTab} via {@link OpenChangeOwnerScreenPayload}. Added
 * 2026-11-17, explicit two-part request: (1) let a Mayor/Town Planner reassign a plot's Owner at any
 * time (independent of {@code zone.PlotPermissions#canManage} -- see that class and {@code
 * OpenPlotConfigSignMenuPayload#canChangeOwner}'s own docs for why this is a deliberately separate,
 * wider gate), and (2) type-ahead suggestions "with players that have logged into the server
 * before." Suggestions are pure client-side prefix filtering over {@link
 * OpenChangeOwnerScreenPayload#knownPlayerNames()} (already sorted, case-insensitive) as the box's
 * {@link EditBox#setResponder} fires -- no new network round trip per keystroke. Submitting always
 * re-resolves the typed name server-side via the real {@code nameToIdCache} (see {@link
 * SetPlotOwnerFromSignPayload}'s own doc), so typing a full name not in this (capped, MRU) list still
 * works correctly -- the list is a convenience, never a hard constraint.
 */
public final class ChangeOwnerScreen extends Screen {

    private static final int MAX_SUGGESTIONS = 6;

    private final OpenChangeOwnerScreenPayload data;
    private EditBox ownerBox;
    private Button setOwnerButton;
    private final List<Button> suggestionButtons = new ArrayList<>();

    public ChangeOwnerScreen(OpenChangeOwnerScreenPayload data) {
        super(Component.literal("Change Owner"));
        this.data = data;
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int y = 40;

        ownerBox = addRenderableWidget(new EditBox(font, centerX - 100, y, 200, 20, Component.literal("Owner")));
        ownerBox.setMaxLength(16);
        ownerBox.setHint(Component.literal("Owner (blank = NPC)"));
        ownerBox.setValue(data.currentOwnerName());
        ownerBox.setResponder(text -> {
            refreshSuggestions(text);
            setOwnerButton.active = !text.isBlank();
        });
        y += 30;

        setOwnerButton = addRenderableWidget(Button.builder(Component.literal("Set Owner"), b -> setOwner(ownerBox.getValue()))
                .bounds(centerX - 100, y, 200, 20).build());
        setOwnerButton.active = !ownerBox.getValue().isBlank();
        y += 30;

        addRenderableWidget(Button.builder(Component.literal("Clear (NPC-owned)"), b -> setOwner(""))
                .bounds(centerX - 100, y, 200, 20).build());
        y += 40;

        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(centerX - 50, y, 100, 20).build());

        refreshSuggestions(ownerBox.getValue());
    }

    /** Up to {@link #MAX_SUGGESTIONS} known names prefix-matching (case-insensitive) what's typed so far, rebuilt as real clickable rows on every keystroke (removed and re-added via {@link #removeWidget}/{@link #addRenderableWidget}, since the candidate set changes with the text). */
    private void refreshSuggestions(String typed) {
        for (Button button : suggestionButtons) {
            removeWidget(button);
        }
        suggestionButtons.clear();
        if (typed.isBlank()) {
            return;
        }
        int centerX = width / 2;
        int y = 100;
        int shown = 0;
        for (String name : data.knownPlayerNames()) {
            if (shown >= MAX_SUGGESTIONS) {
                break;
            }
            if (!name.regionMatches(true, 0, typed, 0, typed.length()) || name.equalsIgnoreCase(typed)) {
                continue;
            }
            Button suggestion = addRenderableWidget(Button.builder(Component.literal(name), b -> applySuggestion(name))
                    .bounds(centerX - 100, y, 200, 16).build());
            suggestionButtons.add(suggestion);
            y += 18;
            shown++;
        }
    }

    private void applySuggestion(String name) {
        ownerBox.setValue(name);
        setOwnerButton.active = true;
        refreshSuggestions(name);
    }

    private void setOwner(String ownerName) {
        send(new SetPlotOwnerFromSignPayload(data.signPos(), ownerName));
        onClose();
    }

    private void send(CustomPacketPayload payload) {
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(payload));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int titleWidth = font.width(title);
        graphics.text(font, title, width / 2 - titleWidth / 2, 15, 0xFFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
