package com.github.cerealklla.settlemynts.zone;

import java.util.UUID;

/**
 * Shared marker for every real {@code Villager}-subclass NPC a player settlement spawns onto one of
 * its own plots -- {@code resident.ResidentVillagerEntity} (generic plots), {@code
 * farm.FarmerWorkerEntity}, and {@code lumberyard.LumberjackWorkerEntity}. Added 2026-10-09 so the
 * "grant shop access the same way natural settlements do" mechanic ({@code
 * resident.VillagerShopInteractListener}, {@code plotsign.PlotShopProximityTicker}, {@code
 * SettlemyntsMod#resolveShopContext}) only needs one generic check instead of hand-listing all three
 * entity types (and whichever more get added later) -- same reasoning as a plain Java marker
 * interface anywhere else, not a new mechanic of its own.
 */
public interface PlotNpc {

    UUID plotId();

    UUID settlementCoreId();
}
