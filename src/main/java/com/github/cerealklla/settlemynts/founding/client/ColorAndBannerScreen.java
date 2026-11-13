package com.github.cerealklla.settlemynts.founding.client;

import com.github.cerealklla.settlemynts.founding.OpenColorAndBannerScreenPayload;
import com.github.cerealklla.settlemynts.founding.SetKingdomBannerPayload;
import com.github.cerealklla.settlemynts.founding.SetSettlementBannerPayload;
import com.github.cerealklla.settlemynts.founding.SetSettlementColorPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.world.item.DyeColor;

/**
 * A settlement's color/banner menu -- originally backlog item 10, built 2026-11-13 as
 * "{@code CapitalCityKeepScreen}," Capital-City-only. Widened the same day for backlog item 11 to
 * also serve a regular settlement's Town Hall Core (same screen/payloads either way, just
 * different Tier gates and no Kingdom Banner row for a non-Capital settlement -- "Kingdom" still
 * isn't its own concept, it's a second pattern field that only ever exists on a Capital City's
 * Core). Opened from {@code FoundingScreen}'s "Capital City Keep..."/"Settlement Hall..." button
 * (label depends on {@link OpenColorAndBannerScreenPayload#isCapitalCity}).
 *
 * <p>Color is a plain cycle-button, same "click cycles and commits immediately, no separate Set
 * button" convention {@code FoundingScreen}'s own boundary-visibility toggle already uses. Tier
 * gates: a Capital City can set color immediately (Tier 1, "when a Capital City Keep is made") and
 * design its Settlement Banner at Tier 2+ ("Capital City Banner" in the backlog's own wording, the
 * same field); a regular settlement needs Tier 2+ for color and Tier 3+ for the banner (backlog
 * item 11's own wording). Kingdom Banner (Tier 3+) only ever shows for a Capital City.
 */
public final class ColorAndBannerScreen extends Screen {

    private final OpenColorAndBannerScreenPayload data;
    private DyeColor color;
    private Button colorButton;

    public ColorAndBannerScreen(OpenColorAndBannerScreenPayload data) {
        super(Component.literal(data.isCapitalCity() ? "Capital City Keep" : "Settlement Hall"));
        this.data = data;
        this.color = DyeColor.byName(data.colorName(), null);
    }

    private int colorTierRequired() {
        return data.isCapitalCity() ? 1 : 2;
    }

    private int bannerTierRequired() {
        return data.isCapitalCity() ? 2 : 3;
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int y = 50;

        colorButton = addRenderableWidget(Button.builder(colorLabel(), b -> cycleColor())
                .bounds(centerX - 100, y, 200, 20).build());
        colorButton.active = data.townHallTier() >= colorTierRequired();
        y += 30;

        Button settlementBannerButton = addRenderableWidget(Button.builder(
                Component.literal("Design " + (data.isCapitalCity() ? "Capital City" : "Settlement") + " Banner..."),
                b -> openDesigner(data.settlementBannerPatterns(), SetSettlementBannerPayload::new)).bounds(centerX - 100, y, 200, 20).build());
        settlementBannerButton.active = data.townHallTier() >= bannerTierRequired();
        y += 24;

        if (data.isCapitalCity()) {
            Button kingdomBannerButton = addRenderableWidget(Button.builder(Component.literal("Design Kingdom Banner..."),
                    b -> openDesigner(data.kingdomBannerPatterns(), SetKingdomBannerPayload::new)).bounds(centerX - 100, y, 200, 20).build());
            kingdomBannerButton.active = data.townHallTier() >= 3;
            y += 24;
        }

        y += 6;
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(centerX - 50, y, 100, 20).build());
    }

    private void cycleColor() {
        DyeColor[] values = DyeColor.values();
        int index = color == null ? -1 : color.getId();
        color = values[(index + 1) % values.length];
        colorButton.setMessage(colorLabel());
        send(new SetSettlementColorPayload(data.coreEntityId(), color.getName()));
    }

    private Component colorLabel() {
        String label = data.isCapitalCity() ? "Capital City Color" : "Settlement Color";
        return Component.literal(label + ": " + (color == null ? "(not set)" : color.getName()));
    }

    private void openDesigner(net.minecraft.world.level.block.entity.BannerPatternLayers current,
                               BannerDesignerScreen.CommitFactory commitFactory) {
        Minecraft.getInstance().setScreen(new BannerDesignerScreen(this, data.coreEntityId(), color, current, commitFactory));
    }

    private void send(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(payload));
    }

    /** Matches every other Town Hall Core screen's dark backdrop -- see BannerDesignerScreen's own doc, 2026-11-13. */
    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xC0101010);
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
