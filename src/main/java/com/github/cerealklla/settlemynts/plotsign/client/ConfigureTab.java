package com.github.cerealklla.settlemynts.plotsign.client;

import java.util.List;

import com.github.cerealklla.nuclyus.menu.client.NuclyusMenu;
import com.github.cerealklla.nuclyus.menu.client.WidgetHost;
import com.github.cerealklla.settlemynts.mineshaft.MineLootTable;
import com.github.cerealklla.settlemynts.mineshaft.MineSpecialization;
import com.github.cerealklla.settlemynts.plotsign.ClientPlotSignRequests;
import com.github.cerealklla.settlemynts.plotsign.OpenConfigurePlotPayload;
import com.github.cerealklla.settlemynts.plotsign.OpenPlotConfigSignMenuPayload;
import com.github.cerealklla.settlemynts.plotsign.RelocatePlotSignPayload;
import com.github.cerealklla.settlemynts.plotsign.RequestConfigurePlotPayload;
import com.github.cerealklla.settlemynts.plotsign.SetMineSpecializationPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

/**
 * "Configure" tab (tabbed Plot Config Sign rework, 2026-10-23) -- only present when {@code
 * canManage}. Folds the old flat "Configure Plot" screen's "Relocate Plot Sign" button in as-is,
 * plus the new Mineshaft-only "Specialization" control (Owner/Mayor/Town Planners can switch a
 * Mineshaft plot between {@link MineSpecialization#ORE}/{@link MineSpecialization#STONE}/{@link
 * MineSpecialization#EXCAVATION} -- see {@code mineshaft.MineLootTable}'s own doc). The current specialization is fetched fresh via
 * {@link RequestConfigurePlotPayload} every time this tab is shown, same "always refetch" discipline
 * as every other server-backed tab here.
 */
public final class ConfigureTab implements NuclyusMenu {

    private final OpenPlotConfigSignMenuPayload data;
    private WidgetHost host;
    private OpenConfigurePlotPayload configured;
    private Button relocateButton;
    private Button oreButton;
    private Button stoneButton;
    private Button excavationButton;

    public ConfigureTab(OpenPlotConfigSignMenuPayload data) {
        this.data = data;
    }

    @Override
    public Component title() {
        return Component.literal("Configure");
    }

    @Override
    public int order() {
        return 2;
    }

    @Override
    public void attachHost(Screen host) {
        this.host = (WidgetHost) host;
    }

    @Override
    public void onShown() {
        configured = null;
        if (data.isMineshaft()) {
            send(new RequestConfigurePlotPayload(data.signPos()));
        }

        Rect2i area = host.contentArea();
        int centerX = area.getX() + area.getWidth() / 2;
        int y = area.getY() + 20;
        relocateButton = host.addMenuWidget(Button.builder(Component.literal("Relocate Plot Sign"), b -> relocate())
                .bounds(centerX - 100, y, 200, 20).build());
        y += 30;

        if (data.isMineshaft()) {
            oreButton = host.addMenuWidget(Button.builder(Component.literal("Specialization: Ore"), b -> setSpecialization(MineSpecialization.ORE))
                    .bounds(centerX - 235, y, 150, 20).build());
            stoneButton = host.addMenuWidget(Button.builder(Component.literal("Specialization: Stone"), b -> setSpecialization(MineSpecialization.STONE))
                    .bounds(centerX - 75, y, 150, 20).build());
            excavationButton = host.addMenuWidget(Button.builder(Component.literal("Specialization: Excavation"), b -> setSpecialization(MineSpecialization.EXCAVATION))
                    .bounds(centerX + 85, y, 150, 20).build());
            refreshSpecializationButtons();
        }
    }

    @Override
    public void onHidden() {
        if (relocateButton != null) {
            host.removeMenuWidget(relocateButton);
            relocateButton = null;
        }
        if (oreButton != null) {
            host.removeMenuWidget(oreButton);
            oreButton = null;
        }
        if (stoneButton != null) {
            host.removeMenuWidget(stoneButton);
            stoneButton = null;
        }
        if (excavationButton != null) {
            host.removeMenuWidget(excavationButton);
            excavationButton = null;
        }
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, Rect2i area, int mouseX, int mouseY, float partialTick) {
        if (data.isMineshaft() && configured == null) {
            ClientPlotSignRequests.takePendingConfigurePlot().ifPresent(payload -> {
                configured = payload;
                refreshSpecializationButtons();
            });
        }
        if (data.isMineshaft() && configured != null) {
            renderFindOdds(graphics, area);
        }
    }

    // 5 pips per entry, 0 dots at 0% up to 5 dots at this table's own highest weight -- explicit
    // user spec, 2026-10-28: "The scale is 0% (0 dots) to the highest % available in this table (5
    // dots)." A relative rarity gauge, not an absolute percentage -- "highest" only means something
    // once every entry in the current tier+specialization table is in hand (see MineLootTable
    // #entriesFor's own doc).
    private static final int ODDS_COLUMNS = 4;
    private static final int PIP_COUNT = 5;
    private static final int PIP_SIZE = 6;
    private static final int PIP_GAP = 2;
    private static final int ROW_HEIGHT = 20;
    private static final int FILLED_COLOR = 0xFF55AAFF;
    private static final int EMPTY_COLOR = 0xFF888888;

    private void renderFindOdds(GuiGraphicsExtractor graphics, Rect2i area) {
        List<MineLootTable.Entry> entries = MineLootTable.entriesFor(configured.tier(), configured.specialization());
        if (entries.isEmpty()) {
            return;
        }
        int maxWeight = entries.get(0).weight(); // entriesFor is sorted highest-weight-first.

        int headerY = area.getY() + 95;
        graphics.text(Minecraft.getInstance().font,
                Component.literal("Mineshaft Finds -- Tier " + configured.tier() + ", " + configured.specialization().label() + ":"),
                area.getX() + 10, headerY, 0xFFFFFFFF);

        int gridY = headerY + 14;
        int columnWidth = area.getWidth() / ODDS_COLUMNS;
        for (int i = 0; i < entries.size(); i++) {
            MineLootTable.Entry entry = entries.get(i);
            int column = i % ODDS_COLUMNS;
            int row = i / ODDS_COLUMNS;
            int x = area.getX() + column * columnWidth + 10;
            int y = gridY + row * ROW_HEIGHT;

            graphics.item(new ItemStack(entry.item()), x, y);

            int dots = maxWeight <= 0 ? 0 : (int) Math.round(PIP_COUNT * (double) entry.weight() / maxWeight);
            int pipX = x + 20;
            int pipY = y + 5;
            for (int pip = 0; pip < PIP_COUNT; pip++) {
                int px = pipX + pip * (PIP_SIZE + PIP_GAP);
                if (pip < dots) {
                    graphics.fill(px, pipY, px + PIP_SIZE, pipY + PIP_SIZE, FILLED_COLOR);
                } else {
                    graphics.outline(px, pipY, PIP_SIZE, PIP_SIZE, EMPTY_COLOR);
                }
            }
        }
    }

    private void refreshSpecializationButtons() {
        if (configured == null || oreButton == null || stoneButton == null || excavationButton == null) {
            return;
        }
        oreButton.active = configured.specialization() != MineSpecialization.ORE;
        stoneButton.active = configured.specialization() != MineSpecialization.STONE;
        excavationButton.active = configured.specialization() != MineSpecialization.EXCAVATION;
    }

    private void relocate() {
        send(new RelocatePlotSignPayload(data.signPos()));
    }

    private void setSpecialization(MineSpecialization specialization) {
        send(new SetMineSpecializationPayload(data.signPos(), specialization));
        configured = new OpenConfigurePlotPayload(data.signPos(), specialization, configured == null ? 1 : configured.tier());
        refreshSpecializationButtons();
    }

    private void send(CustomPacketPayload payload) {
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(payload));
    }
}
