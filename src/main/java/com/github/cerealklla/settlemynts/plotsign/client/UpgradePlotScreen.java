package com.github.cerealklla.settlemynts.plotsign.client;

import com.github.cerealklla.settlemynts.construction.PlotTierUpgradeCost;
import com.github.cerealklla.settlemynts.construction.PlotTierUpgradeFunding;
import com.github.cerealklla.settlemynts.construction.ResourceCost;
import com.github.cerealklla.settlemynts.plotsign.RequestUpgradePlotPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

/**
 * "Upgrade Plot" screen (added 2026-10-09, explicit request: "same options as upgrading
 * structures") -- opened directly client-side from {@code PlotConfigSignMenuScreen} (no server
 * round-trip needed, since {@link PlotTierUpgradeCost#costFor} is pure/client-computable, same as
 * Lyfe's own station-upgrade tooltip). Raises {@code zone.PlotRecord#tier} by one on success; never
 * touches the Construction Box or its bound Blueprint -- see that field's own class doc for why the
 * two are deliberately separate costs/actions.
 */
public final class UpgradePlotScreen extends Screen {

    private final BlockPos signPos;
    private final int currentTier;

    public UpgradePlotScreen(BlockPos signPos, int currentTier) {
        super(Component.literal("Upgrade Plot"));
        this.signPos = signPos;
        this.currentTier = currentTier;
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int y = 90;

        addRenderableWidget(Button.builder(Component.literal("Upgrade (Plot Resources)"),
                        b -> upgrade(PlotTierUpgradeFunding.FundingOption.ON_HAND))
                .bounds(centerX - 100, y, 200, 20).build());
        y += 24;
        addRenderableWidget(Button.builder(Component.literal("Upgrade (Mix + Gold)"),
                        b -> upgrade(PlotTierUpgradeFunding.FundingOption.MIX))
                .bounds(centerX - 100, y, 200, 20).build());
        y += 24;
        addRenderableWidget(Button.builder(Component.literal("Upgrade (Gold Only)"),
                        b -> upgrade(PlotTierUpgradeFunding.FundingOption.GOLD_ONLY))
                .bounds(centerX - 100, y, 200, 20).build());
        y += 30;

        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(centerX - 50, y, 100, 20).build());
    }

    private void upgrade(PlotTierUpgradeFunding.FundingOption option) {
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(
                new RequestUpgradePlotPayload(signPos, option)));
        onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int centerX = width / 2;
        int titleWidth = font.width(title);
        graphics.text(font, title, centerX - titleWidth / 2, 20, 0xFFFFFFFF);

        int nextTier = currentTier + 1;
        String header = "Upgrade to Tier " + nextTier + " costs:";
        graphics.text(font, header, centerX - font.width(header) / 2, 45, 0xFFFFFFFF);
        int lineY = 58;
        for (ResourceCost entry : PlotTierUpgradeCost.costFor(nextTier)) {
            String line = entry.amount() + "x " + entry.resource().label();
            graphics.text(font, line, centerX - font.width(line) / 2, lineY, 0xFFAAAAAA);
            lineY += 10;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
