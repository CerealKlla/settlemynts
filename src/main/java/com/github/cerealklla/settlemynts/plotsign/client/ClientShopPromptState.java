package com.github.cerealklla.settlemynts.plotsign.client;

import java.util.Optional;

import com.github.cerealklla.settlemynts.plotsign.ShopAnchor;

/**
 * Client-side holder for "is there a shop/quest-eligible NPC nearby right now," updated whenever a
 * {@code PlotShopPromptPayload} arrives (2026-10-05). Same shape as Lyfe's {@code
 * location.ClientLocationState} -- harmless if classloaded on a dedicated server, it just never gets
 * written to there.
 *
 * <p>Narrowed to NPC-only 2026-10-16 as part of the NPC Menu overhaul -- see {@link
 * com.github.cerealklla.settlemynts.plotsign.PlotShopPromptPayload}'s own doc. {@code hasWishlist}
 * (the old second-prompt-line gate) is gone along with the second prompt line itself.
 */
public final class ClientShopPromptState {

    private static volatile Optional<ShopAnchor> nearest = Optional.empty();

    private ClientShopPromptState() {
    }

    public static void set(boolean present, ShopAnchor anchor) {
        nearest = present ? Optional.of(anchor) : Optional.empty();
    }

    public static Optional<ShopAnchor> get() {
        return nearest;
    }
}
