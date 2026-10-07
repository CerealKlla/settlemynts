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

    // 2026-10-01: "Private Residence" used to be a native built-in under this id (see
    // SettlemyntsMod's own removal comment) before becoming purely the Blueprynts-bridged
    // blueprynts:private_residence type. Already-finalized plots still have the old id baked into
    // their persisted PlotRecord, and a client mid-update can briefly still send it too -- both
    // would otherwise silently fail every lookup (e.g. "Show Plot Perimeters" skipping the wall, or
    // Finalize rejecting with "Unknown zone type"). Aliased here, not re-registered as a real type,
    // so it never appears twice in the picker's own `all()`-backed list.
    private static final Map<Identifier, Identifier> ALIASES = Map.of(
            Identifier.fromNamespaceAndPath("settlemynts", "private_residence"),
            Identifier.fromNamespaceAndPath("blueprynts", "private_residence"));

    private ZoneTypeRegistry() {
    }

    public static void register(ZoneType type) {
        TYPES.put(type.id(), type);
    }

    public static Optional<ZoneType> get(Identifier id) {
        ZoneType direct = TYPES.get(id);
        if (direct != null) {
            return Optional.of(direct);
        }
        Identifier alias = ALIASES.get(id);
        return alias == null ? Optional.empty() : Optional.ofNullable(TYPES.get(alias));
    }

    public static Collection<ZoneType> all() {
        return List.copyOf(TYPES.values());
    }
}
