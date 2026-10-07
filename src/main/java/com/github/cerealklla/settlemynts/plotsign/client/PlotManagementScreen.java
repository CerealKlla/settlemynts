package com.github.cerealklla.settlemynts.plotsign.client;

import com.github.cerealklla.settlemynts.plotsign.OpenPlotManagementPayload;
import com.github.cerealklla.settlemynts.plotsign.PlotSummaryEntry;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * "Plot Management" (2026-10-05) -- the Town Hall plot's own sign only, a settlement-wide admin
 * list: every plot's Owner, Billing Standing, and any flagged issue, one row per plot. No
 * scrolling yet -- a real v1 limitation, fine for the settlement sizes this mod currently supports.
 */
public final class PlotManagementScreen extends Screen {

    private final OpenPlotManagementPayload data;

    public PlotManagementScreen(OpenPlotManagementPayload data) {
        super(Component.literal("Plot Management"));
        this.data = data;
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int y = 50 + Math.max(1, data.plots().size()) * 26 + 10;
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(centerX - 50, y, 100, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int centerX = width / 2;
        int titleWidth = font.width(title);
        graphics.text(font, title, centerX - titleWidth / 2, 20, 0xFFFFFFFF);

        if (data.plots().isEmpty()) {
            String message = "No plots in this settlement yet.";
            graphics.text(font, message, centerX - font.width(message) / 2, 50, 0xFFAAAAAA);
            return;
        }

        int y = 50;
        for (PlotSummaryEntry entry : data.plots()) {
            String headline = entry.plotName() + " -- Owner: " + entry.ownerDisplay() + " -- " + entry.billingStanding();
            graphics.text(font, headline, centerX - font.width(headline) / 2, y, 0xFFFFFFFF);
            y += 12;
            if (entry.issue().isPresent()) {
                String issue = entry.issue().get();
                graphics.text(font, issue, centerX - font.width(issue) / 2, y, 0xFFFF5555);
            }
            y += 14;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
