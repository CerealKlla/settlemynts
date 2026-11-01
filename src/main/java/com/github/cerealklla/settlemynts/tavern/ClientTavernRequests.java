package com.github.cerealklla.settlemynts.tavern;

import java.util.Optional;

import com.github.cerealklla.nuclyus.request.ClientRequestSlot;

/** Client-side relay for {@link OpenTavernHireMenuPayload} -- same {@code ClientRequestSlot} pattern as {@code guardhouse.ClientGuardhouseRequests}. */
public final class ClientTavernRequests {

    private static final ClientRequestSlot<OpenTavernHireMenuPayload> HIRE_MENU = new ClientRequestSlot<>();

    private ClientTavernRequests() {
    }

    public static void requestHireMenu(OpenTavernHireMenuPayload payload) {
        HIRE_MENU.request(payload);
    }

    public static Optional<OpenTavernHireMenuPayload> takePendingHireMenu() {
        return HIRE_MENU.takePending();
    }
}
