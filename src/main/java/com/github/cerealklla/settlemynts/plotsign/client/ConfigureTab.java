package com.github.cerealklla.settlemynts.plotsign.client;

import com.github.cerealklla.nuclyus.menu.client.NuclyusMenu;
import com.github.cerealklla.nuclyus.menu.client.NuclyusMenuScreen;
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

/**
 * "Configure" tab (tabbed Plot Config Sign rework, 2026-10-23) -- only present when {@code
 * canManage}. Folds the old flat "Configure Plot" screen's "Relocate Plot Sign" button in as-is,
 * plus the new Mineshaft-only "Specialization" control (Owner/Mayor/Town Planners can switch a
 * Mineshaft plot between {@link MineSpecialization#ORE}/{@link MineSpecialization#STONE} -- see
 * {@code mineshaft.MineLootTable}'s own doc). The current specialization is fetched fresh via
 * {@link RequestConfigurePlotPayload} every time this tab is shown, same "always refetch" discipline
 * as every other server-backed tab here.
 */
public final class ConfigureTab implements NuclyusMenu {

    private final OpenPlotConfigSignMenuPayload data;
    private NuclyusMenuScreen host;
    private OpenConfigurePlotPayload configured;
    private Button relocateButton;
    private Button oreButton;
    private Button stoneButton;

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
        this.host = (NuclyusMenuScreen) host;
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
                    .bounds(centerX - 205, y, 200, 20).build());
            stoneButton = host.addMenuWidget(Button.builder(Component.literal("Specialization: Stone"), b -> setSpecialization(MineSpecialization.STONE))
                    .bounds(centerX + 5, y, 200, 20).build());
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
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, Rect2i area, int mouseX, int mouseY, float partialTick) {
        if (data.isMineshaft() && configured == null) {
            ClientPlotSignRequests.takePendingConfigurePlot().ifPresent(payload -> {
                configured = payload;
                refreshSpecializationButtons();
            });
        }
    }

    private void refreshSpecializationButtons() {
        if (configured == null || oreButton == null || stoneButton == null) {
            return;
        }
        oreButton.active = configured.specialization() != MineSpecialization.ORE;
        stoneButton.active = configured.specialization() != MineSpecialization.STONE;
    }

    private void relocate() {
        send(new RelocatePlotSignPayload(data.signPos()));
    }

    private void setSpecialization(MineSpecialization specialization) {
        send(new SetMineSpecializationPayload(data.signPos(), specialization));
        configured = new OpenConfigurePlotPayload(data.signPos(), specialization);
        refreshSpecializationButtons();
    }

    private void send(CustomPacketPayload payload) {
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(payload));
    }
}
