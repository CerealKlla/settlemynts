package com.github.cerealklla.settlemynts.zone;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * What a Zone Type's Shop should be auto-seeded with (design doc Section 14a, added 2026-10-05) --
 * registered per Zone Type via {@code api.Settlemynts#registerShopSeedCatalog} by whichever mod
 * actually understands that type's goods (Blueprynts for Lumberyard/Building Supplier/Stonemason,
 * Lyfe for Armorer/Blacksmith/Grocer/Restaurant) -- Settlemynts itself never hardcodes what any
 * particular business sells. See {@code zone.ShopSeeding} for how/when this gets called.
 */
@FunctionalInterface
public interface ShopSeedCatalog {

    /**
     * @param plotAnchor a representative position on the plot (its Plot Config Sign's own position)
     *                   -- used for e.g. the Lumberyard catalog's biome-local-wood-species lookup.
     * @param plotTier   the plot's current Construction Box Tier (defaults to 1 if unresolvable --
     *                   see {@code ShopSeeding}'s own doc for why). Catalogs that care about Tier
     *                   filter their own entries down to this; catalogs that don't (plain goods)
     *                   ignore it.
     */
    List<SeedListing> seedListingsFor(ServerLevel level, BlockPos plotAnchor, int plotTier);

    /**
     * True for a catalog whose goods genuinely vary by Tier, cumulatively (Armorer/Blacksmith/
     * Restaurant -- {@code seedListingsFor(tier)} includes everything from every tier up to {@code
     * tier}) -- {@code false} (the default) for a catalog whose goods are the same regardless of
     * {@code plotTier} (Grocer, every Blueprynts good). Added 2026-10-10, explicit user request:
     * "shops which sell crafted goods, like armorer and restaurant" should default-list "a random
     * assortment of items from their current tier, and all items from their previous tier" and
     * default their Planned Inventory to "1 of every item for their current tier and below" --
     * {@code zone.ShopSeeding#applyTieredDefaults} is the generic engine for that, gated on this flag
     * so it only ever touches a catalog that actually has tiers to speak of, never Grocer or a plain
     * Blueprynts good whose own goods don't change with Tier at all.
     */
    default boolean hasTierProgression() {
        return false;
    }
}
