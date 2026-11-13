package com.github.cerealklla.settlemynts.founding.client;

import com.github.cerealklla.settlemynts.founding.OpenCapitalCityKeepScreenPayload;
import com.github.cerealklla.settlemynts.founding.SetCapitalCityBannerPayload;
import com.github.cerealklla.settlemynts.founding.SetCapitalCityColorPayload;
import com.github.cerealklla.settlemynts.founding.SetKingdomBannerPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.world.item.DyeColor;

/**
 * The Capital City's own color/banner menu (backlog item 10, added 2026-11-13) -- opened from
 * {@code FoundingScreen}'s "Capital City Keep..." button, only ever shown for a settlement with
 * {@code GhostTownHallCoreEntity#isCapitalCity}. "Same Town Hall structure, just relabeled" per
 * explicit user decision -- this is purely a menu, not a different physical structure/Zone Type.
 *
 * <p>Color is a plain cycle-button, same "click cycles and commits immediately, no separate Set
 * button" convention {@code FoundingScreen}'s own boundary-visibility toggle already uses -- no
 * Tier gate at all ("when a Capital City Keep is made," i.e. from Tier 1). Both banners open
 * {@link BannerDesignerScreen}, gated Tier 2+ (Capital City Banner) / Tier 3+ (Kingdom Banner)
 * against {@link OpenCapitalCityKeepScreenPayload#townHallTier}.
 */
public final class CapitalCityKeepScreen extends Screen {

    private final OpenCapitalCityKeepScreenPayload data;
    private DyeColor color;
    private Button colorButton;

    public CapitalCityKeepScreen(OpenCapitalCityKeepScreenPayload data) {
        super(Component.literal("Capital City Keep"));
        this.data = data;
        this.color = DyeColor.byName(data.colorName(), null);
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int y = 50;

        colorButton = addRenderableWidget(Button.builder(colorLabel(), b -> cycleColor())
                .bounds(centerX - 100, y, 200, 20).build());
        y += 30;

        Button capitalBannerButton = addRenderableWidget(Button.builder(Component.literal("Design Capital City Banner..."),
                b -> openDesigner(data.capitalCityBannerPatterns(), SetCapitalCityBannerPayload::new)).bounds(centerX - 100, y, 200, 20).build());
        capitalBannerButton.active = data.townHallTier() >= 2;
        y += 24;

        Button kingdomBannerButton = addRenderableWidget(Button.builder(Component.literal("Design Kingdom Banner..."),
                b -> openDesigner(data.kingdomBannerPatterns(), SetKingdomBannerPayload::new)).bounds(centerX - 100, y, 200, 20).build());
        kingdomBannerButton.active = data.townHallTier() >= 3;
        y += 30;

        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(centerX - 50, y, 100, 20).build());
    }

    private void cycleColor() {
        DyeColor[] values = DyeColor.values();
        int index = color == null ? -1 : color.getId();
        color = values[(index + 1) % values.length];
        colorButton.setMessage(colorLabel());
        send(new SetCapitalCityColorPayload(data.coreEntityId(), color.getName()));
    }

    private Component colorLabel() {
        return Component.literal("Capital City Color: " + (color == null ? "(not set)" : color.getName()));
    }

    private void openDesigner(net.minecraft.world.level.block.entity.BannerPatternLayers current,
                               BannerDesignerScreen.CommitFactory commitFactory) {
        Minecraft.getInstance().setScreen(new BannerDesignerScreen(this, data.coreEntityId(), color, current, commitFactory));
    }

    private void send(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
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
