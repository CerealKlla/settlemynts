package com.github.cerealklla.settlemynts.plotsign.client;

import com.github.cerealklla.settlemynts.plotsign.OpenNpcMenuPayload;
import com.github.cerealklla.settlemynts.plotsign.RequestFireNpcPayload;
import com.github.cerealklla.settlemynts.plotsign.RequestShopPayload;
import com.github.cerealklla.settlemynts.plotsign.RequestShopWishlistPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * The unified NPC interaction menu -- explicit user spec, 2026-10-16, replacing the old two-key
 * "Press G to open shop" / "Press H to chat" direct-dispatch with a single menu both "G" and a
 * plain right-click open (see {@code SettlemyntsModClient}/{@code resident.VillagerShopInteractListener}).
 * Every button is independently conditional, computed server-side in {@link OpenNpcMenuPayload} --
 * "not every NPC will have every option here."
 *
 * <ul>
 *   <li>"Open Store" -- visible to all; sends the same {@link RequestShopPayload}{@code (anchor,
 *   false)} "Enter Shop" already used.</li>
 *   <li>"Manage Store" -- owner-only on a player-owned plot, Mayor/Town Planners on an NPC-owned
 *   one; sends {@link RequestShopPayload}{@code (anchor, true)}.</li>
 *   <li>"Change Job" -- owner-only, private-residence-plot-only; opens {@link ChangeJobScreen}
 *   directly (no server round trip needed just to show two static buttons).</li>
 *   <li>"Fire NPC" -- owner-only, only once an NPC is actually hired; one-shot {@link
 *   RequestFireNpcPayload}, no confirmation (same convention "Hire NPC" itself already uses).</li>
 *   <li>"Talk" -- NPC-owned plots only; sends the existing {@link RequestShopWishlistPayload},
 *   opening the unchanged {@link ChatMenuScreen}.</li>
 * </ul>
 */
public final class NpcMenuScreen extends Screen {

    private final OpenNpcMenuPayload data;

    public NpcMenuScreen(OpenNpcMenuPayload data) {
        super(Component.literal(data.npcName()));
        this.data = data;
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int y = 50;

        if (data.canOpenStore()) {
            addRenderableWidget(Button.builder(Component.literal("Open Store"), b -> openStore())
                    .bounds(centerX - 100, y, 200, 20).build());
            y += 24;
        }
        if (data.canManageStore()) {
            addRenderableWidget(Button.builder(Component.literal("Manage Store"), b -> manageStore())
                    .bounds(centerX - 100, y, 200, 20).build());
            y += 24;
        }
        if (data.canChangeJob()) {
            addRenderableWidget(Button.builder(Component.literal("Change Job"), b -> changeJob())
                    .bounds(centerX - 100, y, 200, 20).build());
            y += 24;
        }
        if (data.canFireNpc()) {
            addRenderableWidget(Button.builder(Component.literal("Fire NPC"), b -> fireNpc())
                    .bounds(centerX - 100, y, 200, 20).build());
            y += 24;
        }
        if (data.canTalk()) {
            addRenderableWidget(Button.builder(Component.literal("Talk"), b -> talk())
                    .bounds(centerX - 100, y, 200, 20).build());
            y += 24;
        }

        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(centerX - 50, y + 10, 100, 20).build());
    }

    private void openStore() {
        send(new RequestShopPayload(data.anchor(), false));
        onClose();
    }

    private void manageStore() {
        send(new RequestShopPayload(data.anchor(), true));
        onClose();
    }

    private void changeJob() {
        Minecraft.getInstance().setScreen(new ChangeJobScreen(data, this));
    }

    private void fireNpc() {
        send(new RequestFireNpcPayload(data.anchor()));
        onClose();
    }

    private void talk() {
        send(new RequestShopWishlistPayload(data.anchor()));
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
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
