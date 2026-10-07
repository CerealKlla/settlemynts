package com.github.cerealklla.settlemynts.founding.client;

import java.util.List;

import com.github.cerealklla.settlemynts.founding.FinalizeSettlementPayload;
import com.github.cerealklla.settlemynts.founding.GrantTownPlannerPayload;
import com.github.cerealklla.settlemynts.founding.OpenFoundingScreenPayload;
import com.github.cerealklla.settlemynts.founding.RequestPerimeterStakePayload;
import com.github.cerealklla.settlemynts.founding.SetBoundaryVisiblePayload;
import com.github.cerealklla.settlemynts.founding.SetSettlementNamePayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

/**
 * The Ghost Town Hall Core's permissions/naming UI (design doc Section 6) -- opened via {@link
 * OpenFoundingScreenPayload}. Core identity/permission controls only: name entry, granting Town
 * Planner by online player name (founder-only, enforced server-side regardless of what this screen
 * shows), a plain list of current planners, retrieving a Planned Perimeter Stake, the settlement
 * boundary toggle, and Finalize (runs the perimeter auto-fit -- design doc Section 7 -- but not yet
 * the rest of finalization: solidifying stakes/core, Cartographyr registration, or protection,
 * design doc Section 8, a later milestone). All server-side validation (name set, enough stakes,
 * shape encapsulates the core) happens on click rather than being precomputed here, since this
 * screen has no live view of the stakes.
 *
 * <p><b>Split into submenus, 2026-10-06</b> (explicit user request: "the Town Hall Core UI now has
 * too many items in it") -- plot-subdivision controls moved to {@link PlotManagementScreen},
 * Roadways controls to {@link RoadwayManagementScreen}, both opened via a single button each,
 * only once the settlement is finalized (same gating those controls already had inline here).
 */
public final class FoundingScreen extends Screen {

    private final int coreEntityId;
    private final String initialName;
    private final List<String> townPlannerNames;
    private final boolean viewerIsFounder;
    private final boolean finalized;
    private final boolean hasTownHallPlot;
    private boolean showPlotPerimeters;
    private boolean showRoadwayStakes;
    private boolean boundaryVisible;

    private EditBox nameBox;
    private EditBox grantBox;
    private Button boundaryToggleButton;
    private int plannerListY;

    public FoundingScreen(OpenFoundingScreenPayload payload) {
        super(Component.literal("Settlement"));
        this.coreEntityId = payload.coreEntityId();
        this.initialName = payload.settlementName();
        this.townPlannerNames = payload.townPlannerNames();
        this.viewerIsFounder = payload.viewerIsFounder();
        this.boundaryVisible = payload.boundaryVisible();
        this.finalized = payload.finalized();
        this.showPlotPerimeters = payload.showPlotPerimeters();
        this.hasTownHallPlot = payload.hasTownHallPlot();
        this.showRoadwayStakes = payload.showRoadwayStakes();
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int y = 40;

        nameBox = addRenderableWidget(new EditBox(font, centerX - 100, y, 150, 20, Component.literal("Settlement name")));
        nameBox.setMaxLength(64);
        nameBox.setValue(initialName);
        addRenderableWidget(Button.builder(Component.literal("Set Name"), b -> setName())
                .bounds(centerX + 55, y, 90, 20).build());
        y += 30;

        // "Get Perimeter Stake" and "Finalize" only make sense pre-finalize -- fixed 2026-09-26
        // (a real playtest bug: both used to linger after a settlement was already finalized, with
        // no effect other than confusing re-finalize prompts). Plot-stake controls (design doc
        // Section 10-11a) are the finalized-settlement replacement, not yet built here.
        if (!finalized) {
            addRenderableWidget(Button.builder(Component.literal("Get Perimeter Stake"), b -> getStake())
                    .bounds(centerX - 100, y, 245, 20).build());
            y += 30;
        }

        boundaryToggleButton = addRenderableWidget(Button.builder(boundaryToggleLabel(), b -> toggleBoundary())
                .bounds(centerX - 100, y, 245, 20).build());
        y += 30;

        // Plot subdivision and Roadways controls only make sense once the settlement itself is
        // finalized -- these two submenu buttons replace the pre-finalize "Get Perimeter Stake"/
        // "Finalize" row above. Split into separate screens 2026-10-06 (see class doc).
        if (finalized) {
            addRenderableWidget(Button.builder(Component.literal("Plots..."), b -> openPlotManagement())
                    .bounds(centerX - 100, y, 245, 20).build());
            y += 30;

            addRenderableWidget(Button.builder(Component.literal("Roadways..."), b -> openRoadwayManagement())
                    .bounds(centerX - 100, y, 245, 20).build());
            y += 30;
        }

        if (viewerIsFounder) {
            grantBox = addRenderableWidget(new EditBox(font, centerX - 100, y, 150, 20, Component.literal("Player name")));
            grantBox.setMaxLength(16);
            addRenderableWidget(Button.builder(Component.literal("Grant Planner"), b -> grantPlanner())
                    .bounds(centerX + 55, y, 90, 20).build());
            y += 30;
        }

        if (!finalized) {
            addRenderableWidget(Button.builder(Component.literal("Finalize"), b -> finalizeSettlement())
                    .bounds(centerX - 100, y, 245, 20).build());
            y += 30;
        }

        // Rendered below every button (see extractRenderState) -- computed here, not a fixed
        // constant, so it never overlaps the button stack above regardless of whether the
        // founder-only grant row is present. A real layout bug (2026-09-26 playtest round):
        // the planner list and the founder-only grant row previously occupied the same fixed y,
        // visually overlapping whenever more than one planner was listed.
        plannerListY = y + 10;

        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(centerX - 50, height - 30, 100, 20).build());
    }

    private void finalizeSettlement() {
        send(new FinalizeSettlementPayload(coreEntityId));
        onClose();
    }

    private void setName() {
        send(new SetSettlementNamePayload(coreEntityId, nameBox.getValue()));
    }

    private void getStake() {
        send(new RequestPerimeterStakePayload(coreEntityId));
    }

    private void grantPlanner() {
        if (grantBox != null && !grantBox.getValue().isBlank()) {
            send(new GrantTownPlannerPayload(coreEntityId, grantBox.getValue()));
        }
    }

    private void toggleBoundary() {
        boundaryVisible = !boundaryVisible;
        send(new SetBoundaryVisiblePayload(coreEntityId, boundaryVisible));
        boundaryToggleButton.setMessage(boundaryToggleLabel());
    }

    private Component boundaryToggleLabel() {
        return Component.literal("View Settlement Boundaries: " + (boundaryVisible ? "ON" : "OFF"));
    }

    private void openPlotManagement() {
        Minecraft.getInstance().setScreen(new PlotManagementScreen(this, coreEntityId, showPlotPerimeters, hasTownHallPlot));
    }

    private void openRoadwayManagement() {
        Minecraft.getInstance().setScreen(new RoadwayManagementScreen(this, coreEntityId, showRoadwayStakes));
    }

    /** Keeps this screen's own copy in sync so re-opening "Plots..." after a toggle shows the current state, not whatever it was when this screen was first built. */
    public void updateShowPlotPerimeters(boolean showPlotPerimeters) {
        this.showPlotPerimeters = showPlotPerimeters;
    }

    public void updateShowRoadwayStakes(boolean showRoadwayStakes) {
        this.showRoadwayStakes = showRoadwayStakes;
    }

    private void send(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(payload));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int titleWidth = font.width(title);
        graphics.text(font, title, width / 2 - titleWidth / 2, 15, 0xFFFFFF);

        int listY = plannerListY;
        String header = "Town Planners:";
        graphics.text(font, header, width / 2 - 100, listY, 0xAAAAAA);
        listY += 12;
        for (String name : townPlannerNames) {
            graphics.text(font, name, width / 2 - 100, listY, 0xFFFFFF);
            listY += 12;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
