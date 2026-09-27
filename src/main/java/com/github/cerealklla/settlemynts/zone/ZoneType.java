package com.github.cerealklla.settlemynts.zone;

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

/**
 * An open, trust-based plot zone type (design doc Section 11a) -- same governance shape as
 * Cartographyr's {@code EntityType}/{@code Classification}/{@code Layer}: any mod may register its
 * own via {@link ZoneTypeRegistry#register}, no claiming step. Settlemynts itself only ships two
 * built-ins ({@link com.github.cerealklla.settlemynts.SettlemyntsMod}'s {@code TOWN_HALL}/{@code
 * PRIVATE_RESIDENCE}) -- a future Blueprynts mod (and others) is expected to register the rest.
 *
 * @param id the zone type's identity, stored as a plot's Cartographyr {@code designation}
 * @param label a short display label a consumer can show verbatim (e.g. "Town Hall")
 * @param wallBlock the colored block {@code zone.GhostPlotWallEntity} floats for this type's
 *                   "Show Plot Perimeters" walls -- deliberately supplied by whoever registers the
 *                   type (not auto-assigned), so the registering mod controls its own unique color.
 *                   Expected to be a colored wool block while the 16 colors last (switched from
 *                   stained glass 2026-09-26 -- glass read as too see-through/insubstantial for a
 *                   wall); the registering mod is responsible for picking something else (e.g.
 *                   concrete/terracotta) once they run out, same as any other open registry in
 *                   this suite.
 */
public record ZoneType(Identifier id, String label, Block wallBlock) {
}
