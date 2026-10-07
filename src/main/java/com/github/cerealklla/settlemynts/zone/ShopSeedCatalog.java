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
}
