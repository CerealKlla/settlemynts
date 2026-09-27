package com.github.cerealklla.settlemynts.api;

import java.util.Collection;
import java.util.Optional;

import com.github.cerealklla.settlemynts.zone.ZoneType;
import com.github.cerealklla.settlemynts.zone.ZoneTypeRegistry;

import net.minecraft.resources.Identifier;

/**
 * The stable public entry point for other mods to integrate with Settlemynts -- currently just the
 * open plot {@link ZoneType} registry (design doc Section 11a). Same "stable facade, don't reach
 * into internals" pattern as Cartographyr's {@code Cartography}, Lyfe's {@code api.Lyfe}, and
 * Yconomics' {@code api.Yconomics}.
 *
 * <p>Settlemynts itself only ships two built-in zone types ("Town Hall", "Private Residence") --
 * a future Blueprynts mod (and others) is expected to register the rest via {@link
 * #registerZoneType}, e.g. commercial/industrial/farm zones tied to whatever it builds.
 */
public final class Settlemynts {

    private Settlemynts() {
    }

    /** Registers a {@link ZoneType}, no MinecraftServer/ServerLevel param -- same startup-time, in-memory-registry shape as Cartographyr's {@code registerLayer}/{@code registerProtectionLevel}. */
    public static void registerZoneType(ZoneType type) {
        ZoneTypeRegistry.register(type);
    }

    public static Optional<ZoneType> getZoneType(Identifier id) {
        return ZoneTypeRegistry.get(id);
    }

    public static Collection<ZoneType> getRegisteredZoneTypes() {
        return ZoneTypeRegistry.all();
    }
}
