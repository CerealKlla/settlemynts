package com.github.cerealklla.settlemynts.guardhouse.client;

import java.util.Optional;

import com.github.cerealklla.settlemynts.guardhouse.OpenGuardKitStandKytPickerPayload;
import com.github.cerealklla.settlemynts.guardhouse.OpenGuardKitStandMenuPayload;
import com.github.cerealklla.settlemynts.guardhouse.SetStandKytPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * "Select Kyt" for one Guard Kit Stand -- a paged Prev/Next cycle through saved Kyt names, same
 * style as {@code structure.client.KytPickerScreen}/Blueprynts' own {@code BlueprintPickerScreen}
 * (no scrollable list widget exists anywhere in this suite). Keeps the parent {@link
 * GuardKitStandScreen}'s own payload data so picking a name (or Clear) can return straight to a
 * freshly-updated Kit Menu client-side -- same "client-local-then-commit" shape
 * {@code ConfigureGarrisonScreen}'s old row editing used to -- instead of a second server round trip
 * just to redraw.
 */
public final class SelectKytScreen extends Screen {

    private final OpenGuardKitStandKytPickerPayload data;
    private final GuardKitStandScreen parent;
    private int index;
    private Button selectButton;

    public SelectKytScreen(OpenGuardKitStandKytPickerPayload data, GuardKitStandScreen parent) {
        super(Component.literal("Select Kyt"));
        this.data = data;
        this.parent = parent;
    }

    @Override
    protected void init() {
        boolean hasEntries = !data.names().isEmpty();
        int centerX = width / 2;
        int y = 60;

        addRenderableWidget(Button.builder(Component.literal("<"), b -> page(-1))
                .bounds(centerX - 110, y, 20, 20).build()).active = hasEntries;
        addRenderableWidget(Button.builder(Component.literal(">"), b -> page(1))
                .bounds(centerX + 90, y, 20, 20).build()).active = hasEntries;

        selectButton = addRenderableWidget(Button.builder(Component.literal("Select"), b -> choose(data.names().get(index)))
                .bounds(centerX - 75, y + 30, 150, 20).build());
        selectButton.active = hasEntries;

        addRenderableWidget(Button.builder(Component.literal("Clear"), b -> choose(""))
                .bounds(centerX - 75, y + 54, 150, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> Minecraft.getInstance().setScreen(parent))
                .bounds(centerX - 50, y + 84, 100, 20).build());
    }

    private void page(int delta) {
        int count = data.names().size();
        if (count == 0) {
            return;
        }
        index = Math.floorMod(index + delta, count);
    }

    private void choose(String name) {
        send(new SetStandKytPayload(data.standId(), name));

        Optional<String> boundKytName = name.isBlank() ? Optional.empty() : Optional.of(name);
        OpenGuardKitStandMenuPayload updatedData = new OpenGuardKitStandMenuPayload(
                data.standId(), boundKytName, parent.data().guardAlive(), parent.data().secondsUntilRespawn(), parent.data().kytAvailable());
        Minecraft.getInstance().setScreen(new GuardKitStandScreen(updatedData));
    }

    private void send(CustomPacketPayload payload) {
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(payload));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int titleWidth = font.width(title);
        graphics.text(font, title, width / 2 - titleWidth / 2, 30, 0xFFFFFFFF);

        int centerX = width / 2;
        int y = 60;
        if (data.names().isEmpty()) {
            String empty = "No saved Kyts yet.";
            graphics.text(font, empty, centerX - font.width(empty) / 2, y + 4, 0xFFAAAAAA);
            return;
        }
        String name = data.names().get(index);
        String position = "(" + (index + 1) + " / " + data.names().size() + ")";
        graphics.text(font, name, centerX - font.width(name) / 2, y + 4, 0xFFFFFFFF);
        graphics.text(font, position, centerX - font.width(position) / 2, y + 16, 0xFFAAAAAA);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
