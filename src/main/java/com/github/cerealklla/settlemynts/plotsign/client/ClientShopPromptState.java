package com.github.cerealklla.settlemynts.plotsign.client;

import java.util.Optional;

import com.github.cerealklla.settlemynts.plotsign.ShopAnchor;

/**
 * Client-side holder for "is there a shop-eligible Plot Config Sign (or natural-village trading
 * Villager) nearby right now," updated whenever a {@code PlotShopPromptPayload} arrives (2026-10-05,
 * widened to {@link ShopAnchor} 2026-10-08). Same shape as Lyfe's {@code
 * location.ClientLocationState} -- harmless if classloaded on a dedicated server, it just never gets
 * written to there.
 */
public final class ClientShopPromptState {

    private static volatile Optional<ShopAnchor> nearest = Optional.empty();
    private static volatile boolean hasWishlist = false;

    private ClientShopPromptState() {
    }

    public static void set(boolean present, ShopAnchor anchor, boolean hasWishlist) {
        nearest = present ? Optional.of(anchor) : Optional.empty();
        ClientShopPromptState.hasWishlist = present && hasWishlist;
    }

    public static Optional<ShopAnchor> get() {
        return nearest;
    }

    public static boolean hasWishlist() {
        return hasWishlist;
    }
}
