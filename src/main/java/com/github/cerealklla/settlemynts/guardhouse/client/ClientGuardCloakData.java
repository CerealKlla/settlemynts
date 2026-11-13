package com.github.cerealklla.settlemynts.guardhouse.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.github.cerealklla.settlemynts.guardhouse.GuardCloakBannerPayload;

import net.minecraft.world.level.block.entity.BannerPatternLayers;

/**
 * Client-side cache of each tracked guard's current cloak banner pattern, by entity id -- filled in
 * by {@link GuardCloakBannerPayload}'s handler, read by {@link GuardRenderer#extractRenderState}.
 * Mirrors {@code minimap.ClientMinimapState}'s own "harmless to classload on a dedicated server"
 * shape -- nothing here touches a real client-only type ({@link BannerPatternLayers} is a plain
 * world type), so {@code SettlemyntsMod#registerPayloads} (which runs on both sides) can reference
 * this directly from its payload-handler lambda with no {@code Dist} guard needed.
 *
 * <p>Entries for a despawned/out-of-range guard are never explicitly removed -- same accepted,
 * harmless small-map growth as every other per-entity client cache in this suite; guard counts are
 * small and the entity id keyspace is bounded by however many guards a session ever spawns.
 */
public final class ClientGuardCloakData {

    private static final Map<Integer, BannerPatternLayers> PATTERNS_BY_ENTITY_ID = new ConcurrentHashMap<>();

    private ClientGuardCloakData() {
    }

    public static void set(int entityId, BannerPatternLayers patterns) {
        PATTERNS_BY_ENTITY_ID.put(entityId, patterns);
    }

    public static BannerPatternLayers get(int entityId) {
        return PATTERNS_BY_ENTITY_ID.getOrDefault(entityId, BannerPatternLayers.EMPTY);
    }
}
