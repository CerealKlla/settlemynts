package com.github.cerealklla.settlemynts.zone;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.resources.Identifier;

/**
 * In-memory registry of every known {@link ZoneType}, populated by mods calling {@link
 * com.github.cerealklla.settlemynts.api.Settlemynts#registerZoneType} at their own startup -- not
 * persisted, not tied to a specific world, rebuilt fresh every boot. Same trust-based governance
 * already resolved for Cartographyr's {@code EntityType}/{@code Layer}/{@code ProtectionLevel}: no
 * claiming step, registering under an id already present just replaces it.
 */
public final class ZoneTypeRegistry {

    private static final Map<Identifier, ZoneType> TYPES = new ConcurrentHashMap<>();

    private ZoneTypeRegistry() {
    }

    public static void register(ZoneType type) {
        TYPES.put(type.id(), type);
    }

    public static Optional<ZoneType> get(Identifier id) {
        return Optional.ofNullable(TYPES.get(id));
    }

    public static Collection<ZoneType> all() {
        return List.copyOf(TYPES.values());
    }
}
