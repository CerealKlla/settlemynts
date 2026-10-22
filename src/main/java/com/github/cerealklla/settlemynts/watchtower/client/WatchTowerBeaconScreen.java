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
 * The Watch Tower Beacon's furnace-style fuel screen.
 *
 * <p>Real report, 2026-10-22: the button overlapped the inventory, and shift-clicking showed the
 * drawn slot boxes didn't match the real slot positions at all. Root cause of both: this screen used
 * to borrow vanilla's `generic_54` chest texture's first *9-slot* row as background decoration --
 * correct for {@code guardhouse.client.GuardhouseFoodScreen} (a real 9-food-slot row) but wrong here,
 * since this menu only has 1 real slot. Rebuilt on a plain filled panel (no mismatched vanilla art to
 * borrow) with exactly one real "container/slot" sprite drawn at the fuel slot's actual position, and
 * enough vertical room laid out explicitly for the status text/bar/toggle button before the player
 * inventory panel starts -- no more guessing at how much space a borrowed vanilla texture happens to
 * leave. The progress bar reflects a snapshot taken when the screen opened (same "no live resync"
 * precedent {@code GuardhouseFoodScreen}'s own {@code preferredFoodValue} already uses) -- close/
 * reopen to refresh, not wired to a live {@code ContainerData} channel, a deliberate scope decision
 * for this purely cosmetic detail.
 */
public class WatchTowerBeaconScreen extends AbstractContainerScreen<WatchTowerBeaconMenu> {

    private static final Identifier SLOT_SPRITE = Identifier.withDefaultNamespace("container/slot");
    private static final Identifier PLAYER_PANEL_BACKGROUND = Identifier.withDefaultNamespace("textures/gui/container/generic_54.png");
    private static final int PANEL_BORDER = 0xFF8B8B8B;
    private static final int PANEL_FILL = 0xFFC6C6C6;

    private static final int STATUS_LABEL_Y = WatchTowerBeaconMenu.FUEL_SLOT_Y + 18 + 8; // 8px below the slot.
    private static final int BAR_Y = STATUS_LABEL_Y + 10;
    private static final int BAR_WIDTH = 100;
    private static final int BAR_HEIGHT = 6;
    private static final int TOGGLE_ROW_Y = BAR_Y + 14;
    private static final int PLAYER_PANEL_TOP = WatchTowerBeaconMenu.PLAYER_INV_Y - 14;

    public WatchTowerBeaconScreen(WatchTowerBeaconMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 176, PLAYER_PANEL_TOP + 96 + 6);
        this.inventoryLabelY = WatchTowerBeaconMenu.PLAYER_INV_Y - 10;
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
        // Plain filled panel for the top content area -- deliberately not a borrowed vanilla texture
        // slice (see this class's own doc for why that caused the real slot-alignment bug). Covers
        // exactly the real slot + status text + bar + toggle button, nothing more.
        graphics.fill(this.leftPos, this.topPos, this.leftPos + this.imageWidth, this.topPos + PLAYER_PANEL_TOP, PANEL_BORDER);
        graphics.fill(this.leftPos + 1, this.topPos + 1, this.leftPos + this.imageWidth - 1, this.topPos + PLAYER_PANEL_TOP - 1, PANEL_FILL);
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_SPRITE,
                this.leftPos + WatchTowerBeaconMenu.FUEL_SLOT_X - 1, this.topPos + WatchTowerBeaconMenu.FUEL_SLOT_Y - 1, 18, 18);
        // Player-inventory panel is still the real vanilla slice -- correct here, since it really
        // does have 36 real slots matching that art exactly, same proven technique
        // GuardhouseFoodScreen's own identical blit already uses.
        graphics.blit(RenderPipelines.GUI_TEXTURED, PLAYER_PANEL_BACKGROUND, this.leftPos, this.topPos + PLAYER_PANEL_TOP, 0.0F, 126.0F, this.imageWidth, 96, 256, 256);
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
