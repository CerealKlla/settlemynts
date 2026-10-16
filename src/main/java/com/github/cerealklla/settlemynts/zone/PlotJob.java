package com.github.cerealklla.settlemynts.zone;

/**
 * A player-owned plot's own explicit override of what kind of worker its hired NPC should be --
 * "Change Job" on the new NPC Menu, explicit user spec, 2026-10-16: "allows selecting Farmer or
 * Lumberjack for now." {@link #NONE} (the default) means no override -- the worker kind falls back
 * to whatever {@link PlotRecord#zoneTypeId()} would normally imply (see {@link PlotWorkerKind}).
 * Deliberately not Miner/Guardhouse -- the user's own explicit scope, "for now."
 */
public enum PlotJob {
    NONE, FARMER, LUMBERJACK
}
