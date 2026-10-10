package com.github.cerealklla.settlemynts.zone;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.level.ServerLevel;

/**
 * Caches {@link ShopWishlist#hasAnyDeficit} per plot (2026-10-10, real report: "the server seemed a
 * little laggy just now" right after this feature shipped) -- {@code plotsign.PlotShopProximityTicker}
 * was calling the full box-scanning deficit computation for *every* nearby shop NPC on *every* scan
 * (twice a second per online player), to decide whether to show the floating "!". Explicit user
 * direction: "it shouldn't be checking every second if it needs to put up the !, it can check when a
 * player approaches and then just update it when a player interacts with the shop and their
 * inventory change."
 *
 * <p>{@link #hasWishlist} computes (and caches) the real answer only the first time it's asked about
 * a given plot -- i.e. effectively "when a player approaches," since that's the only thing that ever
 * calls this. Every subsequent call (the same player still standing nearby, or a different player
 * approaching the same plot) is a plain map read until something actually invalidates the entry.
 * {@link #invalidate} is called from every real mutation point that could change a plot's deficits:
 * a Buy/Sell (including a wishlist sale), Manage Shop's "Save Changes," Planned Inventory's "Save
 * Changes," and the NPC crafting/midnight-trading engines that move items in or out of a plot's boxes
 * on their own. Deliberately global/static, not per-level -- a `plotId` is already globally unique,
 * same convention as {@code WishlistOfferTracker}'s own key shape.
 */
public final class ShopWishlistCache {

    private ShopWishlistCache() {
    }

    private static final Map<UUID, Boolean> hasWishlistByPlot = new ConcurrentHashMap<>();

    public static boolean hasWishlist(ServerLevel level, PlotRecord plot) {
        return hasWishlistByPlot.computeIfAbsent(plot.plotId(), id -> ShopWishlist.hasAnyDeficit(level, plot));
    }

    public static void invalidate(UUID plotId) {
        hasWishlistByPlot.remove(plotId);
    }
}
