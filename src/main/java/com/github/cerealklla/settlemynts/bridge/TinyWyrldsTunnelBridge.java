package com.github.cerealklla.settlemynts.bridge;

import java.util.List;

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

    /** One real-world door to place as part of a Tunnel group -- this mod's own mirror of {@code
     *  api.TinyWyrlds.TunnelDoorSpec}, so no TinyWyrlds type ever leaks out of this bridge. */
    public record DoorPlacement(ServerLevel level, BlockPos pos, Direction facing) {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded("tinywyrlds");
    }

    /** See {@code api.TinyWyrlds#ensureTunnelRecipeExists} -- idempotent, safe on every call. */
    public static void ensureNoLootTunnelRecipeExists(String recipeName, int roomCount) {
        TinyWyrlds.ensureTunnelRecipeExists(recipeName, roomCount);
    }

    /** See {@code api.TinyWyrlds#createTunnelDoorGroup}. Always {@code PUBLIC} here -- explicit
     *  user spec: "a town placing stakes might always be no loot, no mobs, public." Widened from a
     *  fixed two-door pair to an arbitrary-length {@code doors} list, 2026-11-12, matching the
     *  TinyWyrlds-side generalization -- requires at least 2 doors. */
    public static void placePublicTunnelDoorGroup(List<DoorPlacement> doors, String recipeName) {
        List<TinyWyrlds.TunnelDoorSpec> specs = doors.stream()
                .map(d -> new TinyWyrlds.TunnelDoorSpec(d.level(), d.pos(), d.facing()))
                .toList();
        TinyWyrlds.createTunnelDoorGroup(specs, recipeName, WyrldLifecycle.PUBLIC);
    }
}
