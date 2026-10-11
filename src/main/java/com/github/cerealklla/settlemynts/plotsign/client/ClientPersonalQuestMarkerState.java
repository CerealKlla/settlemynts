package com.github.cerealklla.settlemynts.plotsign.client;

import java.util.List;
import java.util.Set;

/**
 * Client-side holder for the current set of entity ids this player should personally see a "!" head
 * marker above, updated whenever a {@code PersonalQuestMarkerPayload} arrives (2026-10-11). Same
 * shape as {@code ClientShopPromptState} -- harmless if classloaded on a dedicated server, it just
 * never gets written to there. Read by {@code PersonalQuestMarkerRenderer}'s {@code
 * RenderNameTagEvent.CanRender} listener every time a villager's nameplate is about to render.
 */
public final class ClientPersonalQuestMarkerState {

    private static volatile Set<Integer> entityIds = Set.of();

    private ClientPersonalQuestMarkerState() {
    }

    public static void set(List<Integer> ids) {
        entityIds = Set.copyOf(ids);
    }

    public static boolean has(int entityId) {
        return entityIds.contains(entityId);
    }
}
