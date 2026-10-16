package com.github.cerealklla.settlemynts.bridge;

import java.util.List;
import java.util.Optional;

import com.github.cerealklla.kyt.api.Kyt;
import com.github.cerealklla.kyt.loadout.LoadoutRecord;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;

/**
 * "Select Kyt" (and, since the Guard Kit Stand overhaul, "Create Kyt"/"Edit Kyt" too) on a Guard Kit
 * Stand's Kit Menu -- binds a saved Kyt loadout name to a {@code guardhouse.GuardKitStandEntity},
 * read by {@code GuardSpawnTicker} via {@link #getKyt} at spawn time. Optional soft dependency, same
 * isolation convention as {@link BlueprintsConstructionBridge}/{@link ProtectyonsPlotBridge} --
 * every call site elsewhere in Settlemynts must check {@link #isLoaded()} itself before touching
 * this class at all, so its {@code Kyt.*}-referencing bytecode is never loaded on a Kyt-less server.
 */
public final class KytGuardhouseBridge {

    private KytGuardhouseBridge() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded("kyt");
    }

    public static List<String> listKytNames() {
        return Kyt.listKytNames();
    }

    /** {@code GuardSpawnTicker}'s own read -- an empty result (stale/deleted name) is treated the same as unset, exactly per {@code GuardKitStandEntity#boundKytName}'s own doc comment. */
    public static Optional<LoadoutRecord> getKyt(String name) {
        return Kyt.getKyt(name);
    }

    /** "Create Kyt" on the Kit Menu -- see {@code Kyt#openCreateScreen}'s own doc. */
    public static void openCreateScreen(ServerPlayer player, String name) {
        Kyt.openCreateScreen(player, name);
    }

    /** "Edit Kyt" on the Kit Menu -- see {@code Kyt#openEditScreen}'s own doc. */
    public static void openEditScreen(ServerPlayer player, String name) {
        Kyt.openEditScreen(player, name);
    }
}
