package com.github.cerealklla.settlemynts.plotsign.client;

import com.github.cerealklla.settlemynts.plotsign.DefenseSpendingEntry;
import com.github.cerealklla.settlemynts.plotsign.OpenDefenseSpendingMenuPayload;
import com.github.cerealklla.settlemynts.plotsign.SetDefenseSpendingPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

/**
 * "Defense Spending" (explicit user spec, 2026-10-16) -- the Town Hall plot's own sign, listing
 * every Guardhouse in the settlement with an editable gold top-up amount each. {@code
 * guardhouse.DefenseSpendingTransfer} is what actually moves the gold, at midnight. No scrolling
 * yet -- same accepted v1 limitation as {@code PlotManagementScreen}.
 */
public final class DefenseSpendingScreen extends Screen {

    private static final int ROW_HEIGHT = 26;
    private static final int TOP = 50;

    private final OpenDefenseSpendingMenuPayload data;
    private EditBox[] boxes;

    public DefenseSpendingScreen(OpenDefenseSpendingMenuPayload data) {
        super(Component.literal("Defense Spending"));
        this.data = data;
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        boxes = new EditBox[data.guardhouses().size()];
        int y = TOP;
        for (int i = 0; i < data.guardhouses().size(); i++) {
            DefenseSpendingEntry entry = data.guardhouses().get(i);
            EditBox box = addRenderableWidget(new EditBox(font, centerX + 20, y, 60, 20, Component.literal("Allocation")));
            box.setMaxLength(9);
            box.setFilter(s -> s.isEmpty() || s.chars().allMatch(Character::isDigit));
            box.setValue(String.valueOf(entry.currentAllocation()));
            boxes[i] = box;
            int rowIndex = i;
            addRenderableWidget(Button.builder(Component.literal("Set"), b -> setAllocation(rowIndex))
                    .bounds(centerX + 85, y, 50, 20).build());
            y += ROW_HEIGHT;
        }
        y += 10;
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(centerX - 50, y, 100, 20).build());
    }

    private void setAllocation(int rowIndex) {
        DefenseSpendingEntry entry = data.guardhouses().get(rowIndex);
        int value;
        try {
            value = Integer.parseInt(boxes[rowIndex].getValue().trim());
        } catch (NumberFormatException e) {
            value = 0;
        }
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(
                new SetDefenseSpendingPayload(data.signPos(), entry.plotId(), Math.max(0, value))));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int centerX = width / 2;
        int titleWidth = font.width(title);
        graphics.text(font, title, centerX - titleWidth / 2, 20, 0xFFFFFFFF);

        if (data.guardhouses().isEmpty()) {
            String message = "No Guardhouses in this settlement yet.";
            graphics.text(font, message, centerX - font.width(message) / 2, TOP, 0xFFAAAAAA);
            return;
        }

        int y = TOP;
        for (DefenseSpendingEntry entry : data.guardhouses()) {
            graphics.text(font, entry.plotName(), centerX - 150, y + 6, 0xFFFFFFFF);
            y += ROW_HEIGHT;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
