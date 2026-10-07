package com.github.cerealklla.settlemynts.guardhouse;

import java.util.Optional;

/**
 * Client-side bridge for "the Configure Garrison screen should open" -- same zero-client-import
 * poll-once-handoff pattern as {@code plotsign.ClientPlotSignRequests}/{@code
 * founding.ClientFoundingRequests}.
 */
public final class ClientGuardhouseRequests {

    private static volatile OpenConfigureGarrisonPayload pendingMenu;
    private static volatile OpenGarrisonKytPickerPayload pendingKytPicker;

    private ClientGuardhouseRequests() {
    }

    public static void requestMenu(OpenConfigureGarrisonPayload payload) {
        pendingMenu = payload;
    }

    public static Optional<OpenConfigureGarrisonPayload> takePendingMenu() {
        OpenConfigureGarrisonPayload request = pendingMenu;
        pendingMenu = null;
        return Optional.ofNullable(request);
    }

    public static void requestKytPicker(OpenGarrisonKytPickerPayload payload) {
        pendingKytPicker = payload;
    }

    public static Optional<OpenGarrisonKytPickerPayload> takePendingKytPicker() {
        OpenGarrisonKytPickerPayload request = pendingKytPicker;
        pendingKytPicker = null;
        return Optional.ofNullable(request);
    }
}
