package com.github.cerealklla.settlemynts.plotsign.client;

import com.github.cerealklla.settlemynts.plotsign.OpenPlotDetailsPayload;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * "Plot Details" (2026-10-05) -- any plot's own Plot Config Sign, read-only: that plot's Owner,
 * Billing Standing, and any flagged issue (e.g. "No storage placed in plot"). See {@code
 * bills.PlotBillingSummary} for how these are derived.
 */
public final class PlotDetailsScreen extends Screen {

    private final OpenPlotDetailsPayload data;

    public PlotDetailsScreen(OpenPlotDetailsPayload data) {
        super(Component.literal("Plot Details"));
        this.data = data;
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(centerX - 50, 140, 100, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int centerX = width / 2;
        int titleWidth = font.width(title);
        graphics.text(font, title, centerX - titleWidth / 2, 20, 0xFFFFFFFF);

        int y = 50;
        y = line(graphics, "Plot: " + data.entry().plotName(), y, 0xFFFFFFFF);
        y = line(graphics, "Owner: " + data.entry().ownerDisplay(), y, 0xFFFFFFFF);
        y = line(graphics, "Billing Standing: " + data.entry().billingStanding(), y, 0xFFFFFFFF);
        if (data.entry().issue().isPresent()) {
            line(graphics, "Issue: " + data.entry().issue().get(), y, 0xFFFF5555);
        }
    }

    private int line(GuiGraphicsExtractor graphics, String text, int y, int color) {
        graphics.text(font, text, width / 2 - font.width(text) / 2, y, color);
        return y + 14;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
