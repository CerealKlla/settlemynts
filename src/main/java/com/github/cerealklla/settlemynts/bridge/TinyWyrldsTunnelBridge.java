package com.github.cerealklla.settlemynts.bridge;

import com.github.cerealklla.tinywyrlds.api.TinyWyrlds;
import com.github.cerealklla.tinywyrlds.instance.WyrldLifecycle;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;

/**
 * Settlemynts-side bridge for TinyWyrlds' Tunnel feature -- Settlemynts -> TinyWyrlds direction,
 * same isolation convention as every other bridge class in this mod (see {@code
 * AudybleMusicBridge}'s own doc): every call site elsewhere in this mod checks {@link #isLoaded()}
 * first, and this class's own public methods never leak a TinyWyrlds type out.
 */
public final class TinyWyrldsTunnelBridge {

    private TinyWyrldsTunnelBridge() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded("tinywyrlds");
    }

    /** See {@code api.TinyWyrlds#ensureTunnelRecipeExists} -- idempotent, safe on every call. */
    public static void ensureNoLootTunnelRecipeExists(String recipeName, int roomCount) {
        TinyWyrlds.ensureTunnelRecipeExists(recipeName, roomCount);
    }

    /** See {@code api.TinyWyrlds#createTunnelDoorPair}. Always {@code PUBLIC} here -- explicit
     *  user spec: "a town placing stakes might always be no loot, no mobs, public." */
    public static void placePublicTunnelDoorPair(ServerLevel levelA, BlockPos posA, Direction facingA,
                                                   ServerLevel levelB, BlockPos posB, Direction facingB, String recipeName) {
        TinyWyrlds.createTunnelDoorPair(levelA, posA, facingA, levelB, posB, facingB, recipeName, WyrldLifecycle.PUBLIC);
    }
}
