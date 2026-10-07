package com.github.cerealklla.settlemynts.bridge;

import java.util.Set;
import java.util.UUID;

import com.github.cerealklla.cartographyr.geo.Geometry;
import com.github.cerealklla.protectyons.api.Protectyons;

import net.minecraft.server.level.ServerLevel;

/**
 * Registers a finalized Plot's polygon and permitted players with Protectyons' own permission
 * registry -- this class must only ever be referenced (its methods called, or this class
 * loaded/classload-triggered at all) from behind a {@code ModList.get().isLoaded("protectyons")}
 * check at the call site, same isolation every other optional sibling-mod dependency in this suite
 * uses, so a Protectyons-less server never force-loads Protectyons classes.
 *
 * <p><b>Deliberately separate from this plot's own Cartographyr registration</b> (design discussion
 * with the user, 2026-09-29): "Cartographyr doesn't need to know anything about player permissions.
 * Cartographyr knows areas of the world... Settlemynt creates the Plot which houses the Construction
 * Box (for defining who owns the plot, NPC or specific player name)... it needs to tell Cartographyr
 * the Polygon for the world tracking, and it needs to tell Protectyons the Polygon for block editing
 * permissions." Both registrations use the exact same {@code Geometry.Polygon} and the plot's own
 * {@code plotSessionId} (reused as {@code PlotRecord#plotId}) as the shared area id, but are
 * otherwise two completely independent calls to two completely independent mods.
 *
 * <p>Permitted players (confirmed with the user, same day): the settlement's founder ("Mayor") and
 * every granted Town Planner -- {@code GhostTownHallCoreEntity#getTownPlanners()} already includes
 * the founder (added automatically at settlement creation), so that one set covers both roles. A
 * specific per-plot Owner is NOT yet included -- Settlemynts has no Owner-designation feature built
 * yet (see decisions.md's "Owner designation" note in the intended-design walkthrough); once one
 * exists, its assigned player should be added to the set passed here too.
 */
public final class ProtectyonsPlotBridge {

    private ProtectyonsPlotBridge() {
    }

    public static void registerPlot(ServerLevel level, UUID plotId, Geometry.Polygon plotPolygon, Set<UUID> permittedPlayers) {
        Protectyons.registerProtectedArea(level.getServer(), plotId, plotPolygon, permittedPlayers);
    }

    /** Called whenever a settlement's Town Planner set changes, for every one of its already-finalized plots. */
    public static void updatePermittedPlayers(ServerLevel level, UUID plotId, Set<UUID> permittedPlayers) {
        Protectyons.updatePermittedPlayers(level.getServer(), plotId, permittedPlayers);
    }

    /**
     * Registers (or idempotently updates, same {@code areaId}) the settlement's own padded perimeter
     * polygon as a Protectyons-permitted area, keyed by the {@code GhostTownHallCoreEntity}'s own
     * UUID -- a separate area/id namespace from any plot's {@code plotId}, no collision risk. Added
     * 2026-10-05 after a real live-test bug: Settlement Finalize promotes the whole settlement's
     * Cartographyr entity to {@code LifecycleState.REALIZED} (surface-protected by the Settlement
     * layer's default {@code ProtectionLevel}), but until now nothing ever registered a permitted
     * set for that area at all -- so the founder was blocked from breaking terrain anywhere in their
     * own settlement until individual plots existed to carve out exemptions. Call at settlement
     * Finalize (mirrors {@link #registerPlot}) with the current Town Planner roster.
     */
    public static void registerSettlement(ServerLevel level, UUID settlementCoreId, Geometry settlementPolygon, Set<UUID> permittedPlayers) {
        Protectyons.registerProtectedArea(level.getServer(), settlementCoreId, settlementPolygon, permittedPlayers);
    }

    /** Called whenever a settlement's Town Planner set changes, to keep the settlement-wide area (not just its plots) in sync. */
    public static void updateSettlementPermittedPlayers(ServerLevel level, UUID settlementCoreId, Set<UUID> permittedPlayers) {
        Protectyons.updatePermittedPlayers(level.getServer(), settlementCoreId, permittedPlayers);
    }
}
