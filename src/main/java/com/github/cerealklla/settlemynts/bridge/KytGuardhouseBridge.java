package com.github.cerealklla.settlemynts.bridge;

import java.util.List;
import java.util.Optional;

import com.github.cerealklla.kyt.api.Kyt;
import com.github.cerealklla.kyt.loadout.LoadoutRecord;

import net.neoforged.fml.ModList;

/**
 * "Select Kyt" on {@code guardhouse.client.ConfigureGarrisonScreen} -- binds a saved Kyt loadout
 * name to a {@code guardhouse.GarrisonSlot}, for a future (not-yet-built) guard-spawning system to
 * read via {@code Kyt#getKyt}. Optional soft dependency, same isolation convention as {@link
 * BlueprintsConstructionBridge}/{@link ProtectyonsPlotBridge} -- every call site elsewhere in
 * Settlemynts must check {@link #isLoaded()} itself before touching this class at all, so its {@code
 * Kyt.*}-referencing bytecode is never loaded on a Kyt-less server.
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

    /** {@code GuardSpawnTicker}'s own read -- an empty result (stale/deleted name) is treated the same as unset, exactly per {@code GarrisonSlot.kytLoadoutName}'s own doc comment. */
    public static Optional<LoadoutRecord> getKyt(String name) {
        return Kyt.getKyt(name);
    }
}
