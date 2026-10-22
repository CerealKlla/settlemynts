package com.github.cerealklla.settlemynts.watchtower.client;

import java.util.List;

import com.github.cerealklla.settlemynts.watchtower.WatchTowerBeaconListPayload;

import net.minecraft.core.GlobalPos;

/** This client's cached copy of every known Watch Tower Beacon's position -- see {@link WatchTowerBeaconListPayload}'s own doc. */
public final class WatchTowerClientState {

    private static List<GlobalPos> known = List.of();

    private WatchTowerClientState() {
    }

    public static void update(List<GlobalPos> beacons) {
        known = beacons;
    }

    public static List<GlobalPos> known() {
        return known;
    }
}
