package com.github.cerealklla.settlemynts.founding.client;

import java.util.List;

import com.github.cerealklla.settlemynts.founding.FinalizeSettlementPayload;
import com.github.cerealklla.settlemynts.founding.GrantTownPlannerPayload;
import com.github.cerealklla.settlemynts.founding.OpenFoundingScreenPayload;
import com.github.cerealklla.settlemynts.founding.RequestColorAndBannerScreenPayload;
import com.github.cerealklla.settlemynts.founding.RequestDefenseSpendingPayload;
import com.github.cerealklla.settlemynts.founding.RequestPerimeterStakePayload;
import com.github.cerealklla.settlemynts.founding.SetBoundaryVisiblePayload;
import com.github.cerealklla.settlemynts.founding.SetRequiredTagsPayload;
import com.github.cerealklla.settlemynts.founding.SetSettlementNamePayload;
import com.github.cerealklla.settlemynts.founding.SetTaxRatePayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Holds every piece of {@code FoundingScreen}'s own business logic/mutable state, separate from
 * the actual host {@code Screen} -- added 2026-11-13 when the Town Hall Core GUI was restyled onto
 * {@code nuclyus.menu.client.NuclyusMenuScreen}'s real tab-bar chrome. That host class is {@code
 * final} (unlike the old plain-{@code Screen} {@code FoundingScreen}, it can't itself carry this
 * logic as a subclass), so every tab holds this context directly instead -- passed into each
 * tab's own constructor, not via {@code NuclyusMenu#attachHost} (which only ever hands over the
 * host {@code Screen} itself, for widget placement and dismissal).
 *
 * <p>{@link #hostScreen} is attached once, right after the real {@code NuclyusMenuScreen} is
 * constructed (see {@code FoundingScreen#open}) -- the one piece of state genuinely unavailable
 * until after construction, since the tab list (which already needs this context to exist) is
 * itself a constructor argument of that screen.
 */
final class TownHallContext {

    private final int coreEntityId;
    private final String initialName;
    private final List<String> townPlannerNames;
    private final boolean viewerIsFounder;
    private final boolean finalized;
    private final boolean hasTownHallPlot;
    private boolean showPlotPerimeters;
    private boolean showRoadwayStakes;
    private boolean boundaryVisible;
    private final int initialTaxPerItem;
    private final String initialRequiredTags;
    private final boolean isCapitalCity;

    private Screen hostScreen;

    TownHallContext(OpenFoundingScreenPayload payload) {
        this.coreEntityId = payload.coreEntityId();
        this.initialName = payload.settlementName();
        this.townPlannerNames = payload.townPlannerNames();
        this.viewerIsFounder = payload.viewerIsFounder();
        this.boundaryVisible = payload.boundaryVisible();
        this.finalized = payload.finalized();
        this.showPlotPerimeters = payload.showPlotPerimeters();
        this.hasTownHallPlot = payload.hasTownHallPlot();
        this.showRoadwayStakes = payload.showRoadwayStakes();
        this.initialTaxPerItem = payload.taxPerItem();
        this.initialRequiredTags = payload.requiredTags();
        this.isCapitalCity = payload.isCapitalCity();
    }

    void attachHostScreen(Screen hostScreen) {
        this.hostScreen = hostScreen;
    }

    Screen hostScreen() {
        return hostScreen;
    }

    boolean finalized() {
        return finalized;
    }

    boolean viewerIsFounder() {
        return viewerIsFounder;
    }

    boolean isCapitalCity() {
        return isCapitalCity;
    }

    String initialName() {
        return initialName;
    }

    List<String> townPlannerNames() {
        return townPlannerNames;
    }

    boolean boundaryVisible() {
        return boundaryVisible;
    }

    int initialTaxPerItem() {
        return initialTaxPerItem;
    }

    String initialRequiredTags() {
        return initialRequiredTags;
    }

    void finalizeSettlement() {
        send(new FinalizeSettlementPayload(coreEntityId));
        Minecraft.getInstance().setScreen(null);
    }

    void setName(String name) {
        send(new SetSettlementNamePayload(coreEntityId, name));
    }

    void setTax(int value) {
        send(new SetTaxRatePayload(coreEntityId, Math.max(0, value)));
    }

    void setRequiredTags(String tags) {
        send(new SetRequiredTagsPayload(coreEntityId, tags));
    }

    void getStake() {
        send(new RequestPerimeterStakePayload(coreEntityId));
    }

    void grantPlanner(String name) {
        if (!name.isBlank()) {
            send(new GrantTownPlannerPayload(coreEntityId, name));
        }
    }

    /** Flips the boundary-visibility flag, sends the update, and returns the new value so the calling tab can refresh its own button label. */
    boolean toggleBoundaryVisible() {
        boundaryVisible = !boundaryVisible;
        send(new SetBoundaryVisiblePayload(coreEntityId, boundaryVisible));
        return boundaryVisible;
    }

    void openPlotManagement() {
        Minecraft.getInstance().setScreen(new PlotManagementScreen(this, coreEntityId, showPlotPerimeters, hasTownHallPlot));
    }

    void openRoadwayManagement() {
        Minecraft.getInstance().setScreen(new RoadwayManagementScreen(this, coreEntityId, showRoadwayStakes));
    }

    void openDefenseSpending() {
        send(new RequestDefenseSpendingPayload(coreEntityId));
        Minecraft.getInstance().setScreen(null);
    }

    void openColorAndBannerScreen() {
        send(new RequestColorAndBannerScreenPayload(coreEntityId));
        Minecraft.getInstance().setScreen(null);
    }

    /** Keeps this context's own copy in sync so re-opening "Plots" after a toggle shows the current state, not whatever it was when this screen was first built. */
    void updateShowPlotPerimeters(boolean showPlotPerimeters) {
        this.showPlotPerimeters = showPlotPerimeters;
    }

    void updateShowRoadwayStakes(boolean showRoadwayStakes) {
        this.showRoadwayStakes = showRoadwayStakes;
    }

    void send(CustomPacketPayload payload) {
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(payload));
    }
}
