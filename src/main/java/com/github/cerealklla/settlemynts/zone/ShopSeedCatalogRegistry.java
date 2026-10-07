package com.github.cerealklla.settlemynts.zone;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.resources.Identifier;

/**
 * In-memory registry of every known {@link ShopSeedCatalog}, keyed by Zone Type id -- same
 * trust-based, startup-populated, non-persisted shape as {@link ZoneTypeRegistry}. Registering
 * under an id already present just replaces it.
 */
public final class ShopSeedCatalogRegistry {

    private static final Map<Identifier, ShopSeedCatalog> CATALOGS = new ConcurrentHashMap<>();

    private ShopSeedCatalogRegistry() {
    }

    public static void register(Identifier zoneTypeId, ShopSeedCatalog catalog) {
        CATALOGS.put(zoneTypeId, catalog);
    }

    public static Optional<ShopSeedCatalog> get(Identifier zoneTypeId) {
        return Optional.ofNullable(CATALOGS.get(zoneTypeId));
    }
}
