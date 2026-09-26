package com.github.cerealklla.settlemynts.founding;

import java.util.Optional;

/**
 * Client-side bridge for "a Settlemynts screen should open" -- deliberately zero client-only
 * imports, same reasoning as Lyfe's {@code knowledge.ClientWritingRequest}: this is written from
 * the common {@code SettlemyntsMod#registerPayloads} handler (which must stay harmless to
 * class-load on a dedicated server), and read/cleared from a genuinely client-only tick listener
 * in {@code SettlemyntsModClient} that's the only place actually allowed to touch {@code
 * Minecraft}/{@code Screen}. Holds both screen requests (founding, stake) in one place rather than
 * duplicating this poll-once-handoff pattern twice.
 */
public final class ClientFoundingRequests {

    private static volatile OpenFoundingScreenPayload pendingFounding;
    private static volatile OpenStakeScreenPayload pendingStake;

    private ClientFoundingRequests() {
    }

    public static void requestFoundingScreen(OpenFoundingScreenPayload payload) {
        pendingFounding = payload;
    }

    public static Optional<OpenFoundingScreenPayload> takePendingFoundingScreen() {
        OpenFoundingScreenPayload request = pendingFounding;
        pendingFounding = null;
        return Optional.ofNullable(request);
    }

    public static void requestStakeScreen(OpenStakeScreenPayload payload) {
        pendingStake = payload;
    }

    public static Optional<OpenStakeScreenPayload> takePendingStakeScreen() {
        OpenStakeScreenPayload request = pendingStake;
        pendingStake = null;
        return Optional.ofNullable(request);
    }
}
