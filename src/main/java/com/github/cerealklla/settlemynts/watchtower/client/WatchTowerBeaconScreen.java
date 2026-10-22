package com.github.cerealklla.settlemynts.watchtower.client;

import com.github.cerealklla.settlemynts.watchtower.SetWatchTowerEnabledPayload;
import com.github.cerealklla.settlemynts.watchtower.WatchTowerBeaconMenu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

/**
 * The Watch Tower Beacon's furnace-style fuel screen -- reuses the vanilla `generic_54` background
 * (same technique {@code guardhouse.client.GuardhouseFoodScreen} uses for its own single-row panel),
 * plus a plain filled-rectangle flame-progress bar (no vanilla furnace sprite coordinates needed)
 * and an Enable/Disable toggle for the fuel-doubling bonus. The progress bar reflects a snapshot
 * taken when the screen opened (same "no live resync" precedent {@code GuardhouseFoodScreen}'s own
 * {@code preferredFoodValue} already uses) -- close/reopen to refresh, not wired to a live
 * {@code ContainerData} channel, a deliberate scope decision for this purely cosmetic detail.
 */
public class WatchTowerBeaconScreen extends AbstractContainerScreen<WatchTowerBeaconMenu> {

    private static final Identifier BACKGROUND = Identifier.withDefaultNamespace("textures/gui/container/generic_54.png");
    private static final int PLAYER_INV_Y = 84;
    private static final int FUEL_PANEL_BOTTOM = 18 + 17;
    private static final int PLAYER_PANEL_TOP = PLAYER_INV_Y - 14;
    private static final int STATUS_LABEL_Y = FUEL_PANEL_BOTTOM + 3;
    private static final int BAR_Y = STATUS_LABEL_Y + 10;
    private static final int BAR_WIDTH = 100;
    private static final int BAR_HEIGHT = 6;
    private static final int TOGGLE_ROW_Y = BAR_Y + 14;

    public WatchTowerBeaconScreen(WatchTowerBeaconMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 176, PLAYER_PANEL_TOP + 96 + 6);
        this.inventoryLabelY = PLAYER_INV_Y - 10;
    }

    @Override
    protected void init() {
        super.init();
        int centerX = this.leftPos + this.imageWidth / 2;
        boolean enabled = menu.manuallyEnabled();
        Button toggle = addRenderableWidget(Button.builder(
                        Component.literal(enabled ? "Bonus: Enabled" : "Bonus: Disabled"),
                        b -> toggleEnabled())
                .bounds(centerX - 60, this.topPos + TOGGLE_ROW_Y, 120, 20).build());
        toggle.active = menu.canManage();
    }

    private void toggleEnabled() {
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(
                new SetWatchTowerEnabledPayload(menu.beaconPos(), !menu.manuallyEnabled())));
        this.onClose();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, this.leftPos, this.topPos, 0.0F, 0.0F, this.imageWidth, FUEL_PANEL_BOTTOM, 256, 256);
        graphics.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, this.leftPos, this.topPos + PLAYER_PANEL_TOP, 0.0F, 126.0F, this.imageWidth, 96, 256, 256);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int centerX = this.leftPos + this.imageWidth / 2;
        boolean lit = menu.litTimeRemaining() > 0;
        String label = lit ? "Burning" : "Unlit";
        graphics.text(font, label, centerX - font.width(label) / 2, this.topPos + STATUS_LABEL_Y, 0xFFFFFFFF);

        int barLeft = centerX - BAR_WIDTH / 2;
        int barTop = this.topPos + BAR_Y;
        graphics.fill(barLeft, barTop, barLeft + BAR_WIDTH, barTop + BAR_HEIGHT, 0xFF2B2B2B);
        if (lit && menu.litTotalTime() > 0) {
            int filled = BAR_WIDTH * menu.litTimeRemaining() / menu.litTotalTime();
            graphics.fill(barLeft, barTop, barLeft + filled, barTop + BAR_HEIGHT, 0xFFE07B00);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
