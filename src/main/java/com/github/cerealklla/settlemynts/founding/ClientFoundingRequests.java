package com.github.cerealklla.settlemynts.founding;

import java.util.Optional;

import com.github.cerealklla.nuclyus.request.ClientRequestSlot;
import com.github.cerealklla.settlemynts.zone.OpenPlotStakeScreenPayload;

/**
 * Client-side bridge for "a Settlemynts screen should open" -- deliberately zero client-only
 * imports, same reasoning as Lyfe's {@code knowledge.ClientWritingRequest}: this is written from
 * the common {@code SettlemyntsMod#registerPayloads} handler (which must stay harmless to
 * class-load on a dedicated server), and read/cleared from a genuinely client-only tick listener
 * in {@code SettlemyntsModClient} that's the only place actually allowed to touch {@code
 * Minecraft}/{@code Screen}. Holds every screen request (founding, stake, plot stake) in one place
 * rather than duplicating this poll-once-handoff pattern for each.
 */
public final class ClientFoundingRequests {

    private static final ClientRequestSlot<OpenFoundingScreenPayload> FOUNDING = new ClientRequestSlot<>();
    private static final ClientRequestSlot<OpenStakeScreenPayload> STAKE = new ClientRequestSlot<>();
    private static final ClientRequestSlot<OpenPlotStakeScreenPayload> PLOT_STAKE = new ClientRequestSlot<>();
    private static final ClientRequestSlot<OpenDefenseSpendingMenuPayload> DEFENSE_SPENDING = new ClientRequestSlot<>();

    private ClientFoundingRequests() {
    }

    public static void requestFoundingScreen(OpenFoundingScreenPayload payload) {
        FOUNDING.request(payload);
    }

    public static Optional<OpenFoundingScreenPayload> takePendingFoundingScreen() {
        return FOUNDING.takePending();
    }

    public static void requestStakeScreen(OpenStakeScreenPayload payload) {
        STAKE.request(payload);
    }

    public static Optional<OpenStakeScreenPayload> takePendingStakeScreen() {
        return STAKE.takePending();
    }

    public static void requestPlotStakeScreen(OpenPlotStakeScreenPayload payload) {
        PLOT_STAKE.request(payload);
    }

    public static Optional<OpenPlotStakeScreenPayload> takePendingPlotStakeScreen() {
        return PLOT_STAKE.takePending();
    }

    public static void requestDefenseSpendingScreen(OpenDefenseSpendingMenuPayload payload) {
        DEFENSE_SPENDING.request(payload);
    }

    public static Optional<OpenDefenseSpendingMenuPayload> takePendingDefenseSpendingScreen() {
        return DEFENSE_SPENDING.takePending();
    }
}
