package com.github.cerealklla.settlemynts.guardhouse;

import java.util.List;
import java.util.UUID;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

/**
 * Pushes a fresh {@link GuardEntity#refreshCloakState} to every currently-loaded guard belonging to
 * one settlement -- backlog item 12's own fan-out, called from the few well-defined moments a
 * guard's cloak could actually need to change: the Mayor sets/changes the settlement's color or
 * banner ({@code founding.GhostTownHallCoreEntity#notifyLocalVariantChanged}), and any plot's Tier
 * is raised ({@code construction.PlotTierUpgradeFunding}, both success paths, and {@code
 * SettlemyntsMod#requestUpgradePlot}). Deliberately NOT a ticker -- see {@link GuardSpawnTicker}'s
 * own 2026-10-22 doc for why this suite moved away from polling for guard-state refreshes exactly
 * like this one ("There is an event for gear being added to the box, use that").
 *
 * <p>Same large-world-bounds {@code level.getEntitiesOfClass} scan every other chunk-independent
 * lookup in this mod already uses (a guard has no settlement-indexed registry of its own) -- a
 * guard whose chunk isn't currently loaded is silently skipped, the same accepted scaling
 * simplification every other full-world scan in this mod already carries.
 */
public final class GuardCloakSync {

    private static final double WORLD_SCAN_RADIUS = 3.0E7;

    private GuardCloakSync() {
    }

    public static void refreshForSettlement(ServerLevel level, UUID settlementCoreId) {
        AABB worldBounds = new AABB(-WORLD_SCAN_RADIUS, level.getMinY(), -WORLD_SCAN_RADIUS,
                WORLD_SCAN_RADIUS, level.getMaxY(), WORLD_SCAN_RADIUS);
        List<GuardEntity> guards = level.getEntitiesOfClass(GuardEntity.class, worldBounds,
                guard -> settlementCoreId.equals(guard.getSettlementCoreId()));
        for (GuardEntity guard : guards) {
            guard.refreshCloakState(level);
        }
    }
}
