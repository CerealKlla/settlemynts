package com.github.cerealklla.settlemynts.plotsign.client;

import java.util.ArrayList;
import java.util.List;

import com.github.cerealklla.nuclyus.menu.client.NuclyusMenu;
import com.github.cerealklla.nuclyus.menu.client.NuclyusMenuScreen;
import com.github.cerealklla.settlemynts.plotsign.OpenPlotConfigSignMenuPayload;

import net.minecraft.network.chat.Component;

/**
 * Factory for the Plot Config Sign's tabbed main screen (rework added 2026-10-23, explicit user
 * request: "restructure the Plot Config signs... to something closer to the players K menu, with
 * tabs up top to break up logically grouped functionality"). Reuses Nuclyus's shared {@code
 * menu.client.NuclyusMenuScreen} host directly (its fixed-tab constructor, added the same day) --
 * same tab-bar rendering/input delegation the K menu itself uses, just with a caller-built tab list
 * instead of one collected from {@code NuclyusMenuRegistry} (this is a per-sign, per-open tab set
 * computed from server-sent flags, not a cross-mod registration).
 *
 * <p>Replaces the old flat {@code PlotConfigSignMenuScreen} ("Main Menu") -- every old top-level
 * button is now either folded into a tab's own content ({@link OverviewTab}: Plot Details/Hire NPC/
 * Upgrade Plot; {@link ConfigureTab}: Relocate Plot Sign/Specialization; {@link ShopTab}: Enter/
 * Manage Shop; {@link PlannedInventoryTab}; {@link GarrisonTab}: Get Guard Kit Stands; {@link
 * ManagementTab}: Plot Management/Establish Highway) or still pops a real standalone sub-screen over
 * the whole tabbed host for an action that genuinely needs its own screen ({@code UpgradePlotScreen},
 * {@code EstablishHighwayMapScreen}, the real {@code ShopCartMenu} container menu for buy-mode).
 */
public final class PlotConfigScreen {

    private PlotConfigScreen() {
    }

    public static NuclyusMenuScreen create(OpenPlotConfigSignMenuPayload data) {
        List<NuclyusMenu> tabs = new ArrayList<>();
        tabs.add(new OverviewTab(data));
        if (data.canManage()) {
            tabs.add(new ConfigureTab(data));
        }
        if (!data.hasGarrison() && !data.isTownHall()) {
            tabs.add(new ShopTab(data));
            if (data.canManage()) {
                tabs.add(new PlannedInventoryTab(data));
            }
        }
        if (data.canManage() && data.hasGarrison()) {
            tabs.add(new GarrisonTab(data));
        }
        if (data.canManage() && data.isTownHall()) {
            tabs.add(new ManagementTab(data));
        }
        return new NuclyusMenuScreen(Component.literal("Plot Config Sign"), tabs);
    }
}
