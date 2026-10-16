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
 * <p>"Preferred Food" (added 2026-10-16, explicit user spec) -- a numeric field + "Set" button below
 * the player-inventory panel, editable only for a player who can manage this plot ({@link
 * GuardhouseFoodMenu#canManage()}, resolved server-side and shipped down with the menu -- see that
 * class's own doc). {@code GuardhouseFoodAutoBuy} is what actually spends it, at midnight.
 */
public class GuardhouseFoodScreen extends AbstractContainerScreen<GuardhouseFoodMenu> {

    private static final Identifier BACKGROUND = Identifier.withDefaultNamespace("textures/gui/container/generic_54.png");
    private static final int PLAYER_INV_Y = 84;
    // The vanilla generic_54 player-inventory blit (see extractBackground below) is one fixed 96px
    // image that already closes out with its own bottom border right at PANEL_BOTTOM -- there is no
    // blank space inside it to draw into. The new row has to start well below that real edge, not
    // just below the hotbar's own slot coordinates, or it lands on top of the hotbar artwork (real
    // live report, 2026-10-16, screenshot showed the label and button overlapping the hotbar items).
    private static final int PANEL_BOTTOM = (PLAYER_INV_Y - 14) + 96;
    private static final int PREFERRED_FOOD_LABEL_Y = PANEL_BOTTOM + 8;
    private static final int PREFERRED_FOOD_ROW_Y = PREFERRED_FOOD_LABEL_Y + 10;

    private EditBox preferredFoodBox;

    public GuardhouseFoodScreen(GuardhouseFoodMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 176, PREFERRED_FOOD_ROW_Y + 30);
        this.inventoryLabelY = PLAYER_INV_Y - 10;
    }

    @Override
    protected void init() {
        super.init();
        int centerX = width / 2;
        // Panel is only 176px wide -- keep both widgets' combined span well inside it (the old
        // layout used a 60px-wide button offset +45 from center, which stuck out past the panel's
        // right edge entirely).
        preferredFoodBox = addRenderableWidget(new EditBox(font, centerX - 58, PREFERRED_FOOD_ROW_Y, 60, 20, Component.literal("Preferred Food")));
        preferredFoodBox.setMaxLength(9);
        preferredFoodBox.setFilter(s -> s.isEmpty() || s.chars().allMatch(Character::isDigit));
        preferredFoodBox.setValue(String.valueOf(menu.preferredFoodValue()));
        preferredFoodBox.setEditable(menu.canManage());
        Button setButton = addRenderableWidget(Button.builder(Component.literal("Set"), b -> setPreferredFood())
                .bounds(centerX + 8, PREFERRED_FOOD_ROW_Y, 50, 20).build());
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
        int xo = (this.width - this.imageWidth) / 2;
        int yo = (this.height - this.imageHeight) / 2;
        graphics.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, xo, yo, 0.0F, 0.0F, this.imageWidth, 18 + 17, 256, 256);
        graphics.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, xo, yo + PLAYER_INV_Y - 14, 0.0F, 126.0F, this.imageWidth, 96, 256, 256);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        // This row sits below both background blits (outside the vanilla chest-texture area), over
        // the plain blurred world background, not the light panel -- light text, not dark.
        String label = "Preferred Food:";
        graphics.text(font, label, width / 2 - font.width(label) / 2, PREFERRED_FOOD_LABEL_Y, 0xFFFFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
