package com.github.cerealklla.settlemynts.founding.client;

import java.util.List;

import com.github.cerealklla.nuclyus.menu.client.NuclyusMenu;
import com.github.cerealklla.nuclyus.menu.client.NuclyusMenuScreen;
import com.github.cerealklla.settlemynts.founding.OpenFoundingScreenPayload;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The Ghost Town Hall Core's own right-click menu (design doc Section 6) -- opened via {@link
 * OpenFoundingScreenPayload}.
 *
 * <p><b>Restyled into a real tab host, 2026-11-13</b> -- explicit user request/screenshot
 * ("the entire Town Hall Core GUI should be re-styled to the same style as the Unified character
 * GUIs"). {@link #open} builds a {@code nuclyus.menu.client.NuclyusMenuScreen} (its fixed-tab
 * constructor) -- the exact same structural chrome Settlemynts' own Plot Config Sign already
 * reuses ({@code plotsign.client.PlotConfigScreen}), a real shared tab bar, not just copied
 * colors. This is a static factory, not a {@code Screen} subclass itself, because {@code
 * NuclyusMenuScreen} is declared {@code final} -- all the actual business logic that used to live
 * directly on this class now lives on {@link TownHallContext}, which every tab holds instead.
 *
 * <p>Six tabs: {@link OverviewTab} (name/planners/boundary toggle/pre-finalize controls, always
 * enabled), {@link PlotsTab}/{@link RoadwaysTab}/{@link ColorAndBannerTab}/{@link
 * DefenseSpendingTab} (thin one-button wrappers, disabled until finalized), {@link SettingsTab}
 * (tax/required tags, disabled until finalized).
 *
 * <p><b>Deliberate scope boundary</b>: every sub-screen this menu can open ({@link
 * PlotManagementScreen}, {@link RoadwayManagementScreen}, {@code DefenseSpendingScreen}, {@code
 * ColorAndBannerScreen}, and transitively {@code BannerDesignerScreen}) is completely unchanged in
 * its own logic -- only its background/palette was restyled to match (see each file's own doc).
 * Folding those into tabs too would mean threading their existing async request/response round
 * trips (Defense Spending and Color & Banner both need a fresh server round trip before they have
 * anything to show) into this one shared host, which was judged not worth the risk right before a
 * live Production test of everything built today -- a tab here just triggers the exact same
 * request-then-open flow the old flat button list already used.
 */
public final class FoundingScreen {

    private FoundingScreen() {
    }

    public static Screen open(OpenFoundingScreenPayload payload) {
        TownHallContext context = new TownHallContext(payload);
        List<NuclyusMenu> tabs = List.of(
                new OverviewTab(context),
                new PlotsTab(context),
                new RoadwaysTab(context),
                new ColorAndBannerTab(context),
                new DefenseSpendingTab(context),
                new SettingsTab(context));
        NuclyusMenuScreen screen = new NuclyusMenuScreen(Component.literal("Settlement"), tabs);
        context.attachHostScreen(screen);
        return screen;
    }
}
