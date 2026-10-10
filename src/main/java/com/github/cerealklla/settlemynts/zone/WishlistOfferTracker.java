package com.github.cerealklla.settlemynts.zone;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.resources.Identifier;

/**
 * In-memory "this player asked this shop what it needs, so a premium direct sale is open for a
 * while" tracker (2026-10-10, see {@link ShopWishlist}'s own doc for the full feature). Session-only
 * by design, same as {@code plotsign.PlotShopProximityTicker}'s own {@code lastSent} map or {@code
 * zone.ShopCraftingDebounceTicker}'s debounce state -- this is a short-lived UX nudge ("you have
 * {@link ShopWishlist#OFFER_WINDOW_TICKS} to bring me these at a premium"), not real persisted
 * economy state, so losing it on a server restart is an accepted, deliberate simplification: the
 * player just asks again.
 */
public final class WishlistOfferTracker {

    private WishlistOfferTracker() {
    }

    private record Key(UUID playerId, UUID plotId, Identifier resourceKey, boolean isTag) {
    }

    private static final Map<Key, Long> expiresAtGameTime = new ConcurrentHashMap<>();

    public static void grant(UUID playerId, UUID plotId, ShopResource resource, long expiresAtGameTime) {
        Identifier key = resource.tag().isPresent() ? resource.tag().get().location() : resource.itemId().orElseThrow();
        boolean isTag = resource.tag().isPresent();
        WishlistOfferTracker.expiresAtGameTime.put(new Key(playerId, plotId, key, isTag), expiresAtGameTime);
    }

    public static boolean isActive(UUID playerId, UUID plotId, ShopResource resource, long currentGameTime) {
        Identifier key = resource.tag().isPresent() ? resource.tag().get().location() : resource.itemId().orElseThrow();
        boolean isTag = resource.tag().isPresent();
        Long expiry = expiresAtGameTime.get(new Key(playerId, plotId, key, isTag));
        return expiry != null && currentGameTime < expiry;
    }

    /** Called once a sale through the offer actually goes through -- "that temporary option to sell it to him would then go away" (explicit user spec). */
    public static void clear(UUID playerId, UUID plotId, ShopResource resource) {
        Identifier key = resource.tag().isPresent() ? resource.tag().get().location() : resource.itemId().orElseThrow();
        boolean isTag = resource.tag().isPresent();
        expiresAtGameTime.remove(new Key(playerId, plotId, key, isTag));
    }
}
