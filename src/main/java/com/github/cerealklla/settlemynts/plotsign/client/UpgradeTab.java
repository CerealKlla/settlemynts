package com.github.cerealklla.settlemynts.plotsign.client;

import com.github.cerealklla.nuclyus.menu.client.NuclyusMenu;
import com.github.cerealklla.nuclyus.menu.client.WidgetHost;
import com.github.cerealklla.settlemynts.construction.PlotTierUpgradeFunding;
import com.github.cerealklla.settlemynts.plotsign.ClientPlotSignRequests;
import com.github.cerealklla.settlemynts.plotsign.OpenPlotConfigSignMenuPayload;
import com.github.cerealklla.settlemynts.plotsign.RequestUpgradePlotPayload;
import com.github.cerealklla.settlemynts.plotsign.RequestUpgradePlotPreviewPayload;
import com.github.cerealklla.settlemynts.plotsign.UpgradePlotPreviewPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * "Upgrade" tab (tabbed Plot Config Sign rework, 2026-10-23 -- folded in from the old pop-up {@code
 * client.UpgradePlotScreen} per explicit user request: "merge this menu into the new unified plot
 * menu"). Only present when {@code canManage && hasConstructionBox && tier < 5 && tier <
 * maxPlotTier} -- same gate the old Main Menu button used. Auto-fetches the cost preview on {@code
 * onShown()} (same "always refetch" discipline as every other server-backed tab here) instead of
 * needing its own trigger button first. A successful upgrade re-requests the preview in place
 * (showing the next Tier's cost) rather than closing anything -- if the plot has since hit Tier 5 or
 * the Town Hall's own cap, the server silently sends nothing back (same "button shouldn't have been
 * shown at all" no-op {@code SettlemyntsMod#requestUpgradePlotPreview} already used), which just
 * leaves this tab showing "Loading..." until the sign is reopened fresh -- a minor, accepted
 * edge case rather than reworking the tab list to shrink live.
 */
public final class UpgradeTab implements NuclyusMenu {

    private final OpenPlotConfigSignMenuPayload data;
    private WidgetHost host;
    private UpgradePlotPreviewPayload preview;
    private Button onHandButton;
    private Button mixButton;
    private Button goldButton;

    public UpgradeTab(OpenPlotConfigSignMenuPayload data) {
        this.data = data;
    }

    @Override
    public Component title() {
        return Component.literal("Upgrade");
    }

    @Override
    public int order() {
        return 1;
    }

    @Override
    public void attachHost(Screen host) {
        this.host = (WidgetHost) host;
    }

    @Override
    public void onShown() {
        preview = null;
        requestPreview();

        Rect2i area = host.contentArea();
        int centerX = area.getX() + area.getWidth() / 2;
        int y = area.getY() + area.getHeight() - 94;
        onHandButton = host.addMenuWidget(Button.builder(Component.literal("Upgrade (Plot Resources)"),
                        b -> upgrade(PlotTierUpgradeFunding.FundingOption.ON_HAND))
                .bounds(centerX - 110, y, 220, 20).build());
        onHandButton.active = false;
        y += 24;
        mixButton = host.addMenuWidget(Button.builder(Component.literal("Upgrade (Mix + Gold)"),
                        b -> upgrade(PlotTierUpgradeFunding.FundingOption.MIX))
                .bounds(centerX - 110, y, 220, 20).build());
        mixButton.active = false;
        y += 24;
        goldButton = host.addMenuWidget(Button.builder(Component.literal("Upgrade (Gold Only)"),
                        b -> upgrade(PlotTierUpgradeFunding.FundingOption.GOLD_ONLY))
                .bounds(centerX - 110, y, 220, 20).build());
        goldButton.active = false;
    }

    @Override
    public void onHidden() {
        if (onHandButton != null) {
            host.removeMenuWidget(onHandButton);
            onHandButton = null;
        }
        if (mixButton != null) {
            host.removeMenuWidget(mixButton);
            mixButton = null;
        }
        if (goldButton != null) {
            host.removeMenuWidget(goldButton);
            goldButton = null;
        }
    }

    private void requestPreview() {
        send(new RequestUpgradePlotPreviewPayload(data.signPos()));
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, Rect2i area, int mouseX, int mouseY, float partialTick) {
        ClientPlotSignRequests.takePendingUpgradePlotPreview().ifPresent(payload -> {
            preview = payload;
            refreshButtons();
        });

        Font font = Minecraft.getInstance().font;
        int centerX = area.getX() + area.getWidth() / 2;
        int y = area.getY() + 20;
        if (preview == null) {
            String message = "Loading...";
            graphics.text(font, message, centerX - font.width(message) / 2, y, 0xFFAAAAAA);
            return;
        }

        String header = "Upgrade to Tier " + preview.nextTier() + " costs:";
        graphics.text(font, header, centerX - font.width(header) / 2, y, 0xFFFFFFFF);
        y += 16;
        for (UpgradePlotPreviewPayload.ResourceRow row : preview.resources()) {
            String line = row.amount() + "x " + row.label() + " (" + row.onHand() + " on plot)";
            graphics.text(font, line, centerX - font.width(line) / 2, y, 0xFFAAAAAA);
            y += 10;
        }
        y += 10;
        String gold = "Gold: On Plot " + preview.goldOnPlot() + ", On Person " + preview.goldOnPerson();
        graphics.text(font, gold, centerX - font.width(gold) / 2, y, 0xFFFFD700);

        if (!preview.newlyAllowedZoneTypeLabels().isEmpty()) {
            y += 10;
            String allowed = "Buildings Allowed After Upgrade: " + String.join(", ", preview.newlyAllowedZoneTypeLabels());
            graphics.text(font, allowed, centerX - font.width(allowed) / 2, y, 0xFF55FF55);
        }
    }

    private void refreshButtons() {
        if (preview == null) {
            return;
        }
        if (onHandButton != null) {
            onHandButton.active = preview.onHandEnabled();
        }
        if (mixButton != null) {
            mixButton.active = preview.mixEnabled();
            mixButton.setMessage(Component.literal("Upgrade (Mix + Gold) -- " + preview.mixTotalCost() + "g"));
        }
        if (goldButton != null) {
            goldButton.active = preview.goldEnabled();
            goldButton.setMessage(Component.literal("Upgrade (Gold Only) -- " + preview.goldTotalCost() + "g"));
        }
    }

    private void upgrade(PlotTierUpgradeFunding.FundingOption option) {
        send(new RequestUpgradePlotPayload(data.signPos(), option));
        preview = null;
        requestPreview();
    }

    private void send(CustomPacketPayload payload) {
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(payload));
    }
}
