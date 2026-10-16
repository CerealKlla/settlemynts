package com.github.cerealklla.settlemynts.guardhouse;

import java.util.Optional;

/**
 * Client-side bridge for "the Guard Kit Stand's Kit Menu should open" -- same zero-client-import
 * poll-once-handoff pattern as {@code plotsign.ClientPlotSignRequests}/{@code
 * founding.ClientFoundingRequests}.
 */
public final class ClientGuardhouseRequests {

    private static volatile OpenGuardKitStandMenuPayload pendingMenu;
    private static volatile OpenGuardKitStandKytPickerPayload pendingKytPicker;

    private ClientGuardhouseRequests() {
    }

    public static void requestMenu(OpenGuardKitStandMenuPayload payload) {
        pendingMenu = payload;
    }

    public static Optional<OpenGuardKitStandMenuPayload> takePendingMenu() {
        OpenGuardKitStandMenuPayload request = pendingMenu;
        pendingMenu = null;
        return Optional.ofNullable(request);
    }

    public static void requestKytPicker(OpenGuardKitStandKytPickerPayload payload) {
        pendingKytPicker = payload;
    }

    public static Optional<OpenGuardKitStandKytPickerPayload> takePendingKytPicker() {
        OpenGuardKitStandKytPickerPayload request = pendingKytPicker;
        pendingKytPicker = null;
        return Optional.ofNullable(request);
    }
}
