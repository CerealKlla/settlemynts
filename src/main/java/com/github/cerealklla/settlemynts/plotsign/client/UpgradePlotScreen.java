package com.github.cerealklla.settlemynts.plotsign.client;

import com.github.cerealklla.settlemynts.construction.PlotTierUpgradeFunding;
import com.github.cerealklla.settlemynts.plotsign.RequestUpgradePlotPayload;
import com.github.cerealklla.settlemynts.plotsign.UpgradePlotPreviewPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

/**
 * "Upgrade Plot" screen (added 2026-10-09, explicit request: "same options as upgrading
 * structures"; widened the same day after the first live test to show real numbers instead of a
 * static client-computed table -- "The listed cost should have (#) next to each to show how much is
 * currently in the plot, then the two money options should show the live gold cost... using the
 * cheapest of all plot store prices within the settlement, and keeping in mind available stock at
 * those stores. If not enough resources are available... the appropriate buttons should be
 * disabled"). Now entirely server-driven via {@link UpgradePlotPreviewPayload} -- opened only after
 * that round-trip completes (see {@code RequestUpgradePlotPreviewPayload}), never client-computed.
 * Raises {@code zone.PlotRecord#tier} by one on success; never touches the Construction Box or its
 * bound Blueprint -- see that field's own class doc for why the two are deliberately separate costs/
 * actions.
 */
public final class UpgradePlotScreen extends Screen {

    private final UpgradePlotPreviewPayload data;

    public UpgradePlotScreen(UpgradePlotPreviewPayload data) {
        super(Component.literal("Upgrade Plot"));
        this.data = data;
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int rowCount = data.resources().size();
        int extraLines = data.newlyAllowedZoneTypeLabels().isEmpty() ? 0 : 1;
        int y = 46 + rowCount * 10 + 10 + 10 + extraLines * 10 + 14;

        Button onHand = addRenderableWidget(Button.builder(Component.literal("Upgrade (Plot Resources)"),
                        b -> upgrade(PlotTierUpgradeFunding.FundingOption.ON_HAND))
                .bounds(centerX - 110, y, 220, 20).build());
        onHand.active = data.onHandEnabled();
        y += 24;

        Button mix = addRenderableWidget(Button.builder(Component.literal("Upgrade (Mix + Gold) -- " + data.mixTotalCost() + "g"),
                        b -> upgrade(PlotTierUpgradeFunding.FundingOption.MIX))
                .bounds(centerX - 110, y, 220, 20).build());
        mix.active = data.mixEnabled();
        y += 24;

        Button gold = addRenderableWidget(Button.builder(Component.literal("Upgrade (Gold Only) -- " + data.goldTotalCost() + "g"),
                        b -> upgrade(PlotTierUpgradeFunding.FundingOption.GOLD_ONLY))
                .bounds(centerX - 110, y, 220, 20).build());
        gold.active = data.goldEnabled();
        y += 30;

        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(centerX - 50, y, 100, 20).build());
    }

    private void upgrade(PlotTierUpgradeFunding.FundingOption option) {
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(
                new RequestUpgradePlotPayload(data.signPos(), option)));
        onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int centerX = width / 2;
        int titleWidth = font.width(title);
        graphics.text(font, title, centerX - titleWidth / 2, 20, 0xFFFFFFFF);

        String header = "Upgrade to Tier " + data.nextTier() + " costs:";
        graphics.text(font, header, centerX - font.width(header) / 2, 34, 0xFFFFFFFF);
        int lineY = 46;
        for (UpgradePlotPreviewPayload.ResourceRow row : data.resources()) {
            String line = row.amount() + "x " + row.label() + " (" + row.onHand() + " on plot)";
            graphics.text(font, line, centerX - font.width(line) / 2, lineY, 0xFFAAAAAA);
            lineY += 10;
        }
        lineY += 10;
        String gold = "Gold: On Plot " + data.goldOnPlot() + ", On Person " + data.goldOnPerson();
        graphics.text(font, gold, centerX - font.width(gold) / 2, lineY, 0xFFFFD700);

        if (!data.newlyAllowedZoneTypeLabels().isEmpty()) {
            lineY += 10;
            String allowed = "Buildings Allowed After Upgrade: " + String.join(", ", data.newlyAllowedZoneTypeLabels());
            graphics.text(font, allowed, centerX - font.width(allowed) / 2, lineY, 0xFF55FF55);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
