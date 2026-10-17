package com.github.cerealklla.settlemynts.guardhouse;

import java.util.Optional;

import com.github.cerealklla.nuclyus.request.ClientRequestSlot;

/**
 * Client-side bridge for "the Guard Kit Stand's Kit Menu should open" -- same zero-client-import
 * poll-once-handoff pattern as {@code plotsign.ClientPlotSignRequests}/{@code
 * founding.ClientFoundingRequests}.
 */
public final class ClientGuardhouseRequests {

    private static final ClientRequestSlot<OpenGuardKitStandMenuPayload> MENU = new ClientRequestSlot<>();
    private static final ClientRequestSlot<OpenGuardKitStandKytPickerPayload> KYT_PICKER = new ClientRequestSlot<>();

    private ClientGuardhouseRequests() {
    }

    public static void requestMenu(OpenGuardKitStandMenuPayload payload) {
        MENU.request(payload);
    }

    public static Optional<OpenGuardKitStandMenuPayload> takePendingMenu() {
        return MENU.takePending();
    }

    public static void requestKytPicker(OpenGuardKitStandKytPickerPayload payload) {
        KYT_PICKER.request(payload);
    }

    public static Optional<OpenGuardKitStandKytPickerPayload> takePendingKytPicker() {
        return KYT_PICKER.takePending();
    }
}
