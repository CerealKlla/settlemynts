package com.github.cerealklla.settlemynts.guardhouse.client;

import com.github.cerealklla.settlemynts.guardhouse.GuardhouseFoodMenu;
import com.github.cerealklla.settlemynts.guardhouse.SetPreferredFoodPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

/**
 * The Guardhouse's food Container screen -- a plain one-row inventory, same vanilla `generic_54`
 * background Blueprynts' own `ConstructionBoxScreen` uses.
 *
 * <p>"Preferred Food" (added 2026-10-16, explicit user spec) -- a numeric field + "Set" button in the
 * gap between the food panel and the player-inventory panel, editable only for a player who can manage
 * this plot ({@link
 * GuardhouseFoodMenu#canManage()}, resolved server-side and shipped down with the menu -- see that
 * class's own doc). {@code GuardhouseFoodAutoBuy} is what actually spends it, at midnight.
 */
public class GuardhouseFoodScreen extends AbstractContainerScreen<GuardhouseFoodMenu> {

    private static final Identifier BACKGROUND = Identifier.withDefaultNamespace("textures/gui/container/generic_54.png");
    private static final int PLAYER_INV_Y = 84;
    // The food-container panel and the player-inventory panel are two SEPARATE background blits
    // (see extractBackground below) with a real gap between them -- the food panel's own blit ends
    // at FOOD_PANEL_BOTTOM, and the player-inventory blit doesn't start until PLAYER_PANEL_TOP. Put
    // the "Preferred Food" row in that existing gap instead of appending extra space below the whole
    // screen. All three of these are LOCAL to the panel -- i.e. relative to this.topPos, which is
    // itself a window-size-dependent offset AbstractContainerScreen computes in its own init(). Real
    // bug, 2026-10-16: an earlier version of this fix passed these local values straight into the
    // EditBox/Button/text calls with no `topPos` added, so the row floated at a fixed spot near the
    // top of the whole game window instead of actually tracking the panel -- it happened to visually
    // land near the hotbar at one window size and near the title bar at another, which is why two
    // rounds of "just move the numbers" didn't actually fix anything.
    private static final int FOOD_PANEL_BOTTOM = 18 + 17;
    private static final int PLAYER_PANEL_TOP = PLAYER_INV_Y - 14;
    private static final int PREFERRED_FOOD_LABEL_Y = FOOD_PANEL_BOTTOM + 3;
    private static final int PREFERRED_FOOD_ROW_Y = PREFERRED_FOOD_LABEL_Y + 10;

    private EditBox preferredFoodBox;

    public GuardhouseFoodScreen(GuardhouseFoodMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 176, PLAYER_PANEL_TOP + 96 + 6);
        this.inventoryLabelY = PLAYER_INV_Y - 10;
    }

    @Override
    protected void init() {
        super.init();
        int centerX = this.leftPos + this.imageWidth / 2;
        // Panel is only 176px wide -- keep both widgets' combined span well inside it.
        preferredFoodBox = addRenderableWidget(new EditBox(font, centerX - 58, this.topPos + PREFERRED_FOOD_ROW_Y, 60, 20, Component.literal("Preferred Food")));
        preferredFoodBox.setMaxLength(9);
        preferredFoodBox.setFilter(s -> s.isEmpty() || s.chars().allMatch(Character::isDigit));
        preferredFoodBox.setValue(String.valueOf(menu.preferredFoodValue()));
        preferredFoodBox.setEditable(menu.canManage());
        Button setButton = addRenderableWidget(Button.builder(Component.literal("Set"), b -> setPreferredFood())
                .bounds(centerX + 8, this.topPos + PREFERRED_FOOD_ROW_Y, 50, 20).build());
        setButton.active = menu.canManage();
    }

    private void setPreferredFood() {
        int value;
        try {
            value = Integer.parseInt(preferredFoodBox.getValue().trim());
        } catch (NumberFormatException e) {
            value = 0;
        }
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(
                new SetPreferredFoodPayload(menu.guardhousePos(), Math.max(0, value))));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, this.leftPos, this.topPos, 0.0F, 0.0F, this.imageWidth, 18 + 17, 256, 256);
        graphics.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, this.leftPos, this.topPos + PLAYER_INV_Y - 14, 0.0F, 126.0F, this.imageWidth, 96, 256, 256);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        // This row sits in the real gap between the food panel and the player-inventory panel
        // (outside both vanilla chest-texture blits), over the plain blurred world background, not
        // the light panel -- light text, not dark.
        String label = "Preferred Food:";
        int centerX = this.leftPos + this.imageWidth / 2;
        graphics.text(font, label, centerX - font.width(label) / 2, this.topPos + PREFERRED_FOOD_LABEL_Y, 0xFFFFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
