package com.github.cerealklla.settlemynts.tavern;

import net.minecraft.resources.Identifier;

/**
 * Design doc Section 17's Tavern Plot Type -- shared constants for the full vertical slice built
 * 2026-10-31. {@code TAVERN_ZONE_TYPE_ID} reuses Blueprynts' already-registered stub ({@code
 * BluepryntsMod#commonSetup}, added 2026-10-09) -- same "Settlemynts recognizes the id, Blueprynts
 * owns the Blueprint/structure side" split {@code guardhouse.GuardhouseConstants#GUARDHOUSE_ZONE_TYPE_ID}
 * already uses.
 */
public final class TavernConstants {

    private TavernConstants() {
    }

    public static final Identifier TAVERN_ZONE_TYPE_ID = Identifier.fromNamespaceAndPath("blueprynts", "tavern");

    /**
     * "Each tier of tavern gives you an additional stool" -- simplest reading of the spec, flagged
     * placeholder/tunable same as every other Tier curve in this mod ({@code
     * GuardhouseConstants#capacityForTier}, etc.). {@code tier <= 0} (no bound Blueprint yet) returns 0.
     */
    public static int stoolCountForTier(int tier) {
        return Math.max(0, Math.min(5, tier));
    }
}
