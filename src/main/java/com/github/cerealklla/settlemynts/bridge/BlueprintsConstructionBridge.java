package com.github.cerealklla.settlemynts.bridge;

import java.util.Optional;
import java.util.UUID;

import com.github.cerealklla.blueprynts.api.BuildableArea;
import com.github.cerealklla.blueprynts.api.Blueprynts;
import com.github.cerealklla.blueprynts.api.PlotArea;
import com.github.cerealklla.cartographyr.geo.PlotValidity;
import com.github.cerealklla.settlemynts.zone.PlotSitePlacement;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;

/**
 * Auto-places a Blueprynts **Building Supply Box** (`api.Blueprynts#createConstructionBox`) on a
 * newly-finalized plot -- the reverse direction of Blueprynts' own existing optional dependency on
 * Settlemynts ({@code bridge.SettlemyntsZoneBridge} there). This class must only ever be referenced
 * (its {@link #placeConstructionBox} called, or this class loaded/classload-triggered at all) from
 * behind a {@code ModList.get().isLoaded("blueprynts")} check at the call site -- same isolation
 * every other optional sibling-mod dependency in this suite uses, so a Blueprynts-less server never
 * force-loads Blueprynts classes.
 *
 * <p><b>Corrected 2026-09-29</b>: a first version of this class placed Blueprynts'
 * `ConstructionSiteBlock` -- a completely different, unrelated block (the free-form footprint
 * *authoring* tool) mistaken for the user's actual "Construction Box" request. See Settlemynts'
 * decisions.md, same date, for the mix-up and correction.
 *
 * <p><b>Site-finding extracted to {@link PlotSitePlacement}, 2026-09-30</b> -- this class used to
 * compute its own placement site internally, which meant nothing else could know where a plot's
 * "natural" build site is without going through Blueprynts. The Plot Config Sign needs that same
 * site (to place itself next to it) on every plot, including a Blueprynts-less server where a
 * Building Supply Box (and so this whole class) never runs at all -- see {@code
 * SettlemyntsMod#finalizePlot}, which now finds the site once via {@link PlotSitePlacement#find} and
 * only conditionally hands it to this class.
 */
public final class BlueprintsConstructionBridge {

    private BlueprintsConstructionBridge() {
    }

    /** The placed box's position and its Blueprynts-minted Construction ID (see {@code PlotRecord}). */
    public record Placement(BlockPos pos, UUID constructionId) {
    }

    /**
     * Places a Building Supply Box at {@code site}'s own position, facing into the plot's buildable
     * area, computing and passing a {@link BuildableArea} (the BLUE cells' own bounding box) and a
     * {@link PlotArea} (the whole plot's real per-cell interior shape) so "Reposition Building"/
     * "Reposition Supply Box" can later be checked against the plot's real shape without Blueprynts
     * needing any Cartographyr dependency of its own.
     *
     * @param zoneTypeId this plot's own Zone Type id, passed straight through to {@code
     *                    api.Blueprynts#createConstructionBox} so the box's own Blueprint picker can
     *                    be constrained to matching Blueprint Types -- user request, 2026-09-29.
     */
    public static Placement placeConstructionBox(ServerLevel level, PlotSitePlacement.Site site, Identifier zoneTypeId) {
        BuildableArea buildableArea = blueBoundingBox(site.grid(), site.minX(), site.minZ(), site.maxX(), site.maxZ());
        PlotArea plotArea = plotInteriorArea(site.grid(), site.minX(), site.minZ(), site.maxX(), site.maxZ());
        UUID constructionId = Blueprynts.createConstructionBox(level, site.pos(), site.towardBlue().getOpposite(), zoneTypeId, buildableArea, plotArea);
        return new Placement(site.pos(), constructionId);
    }

    /**
     * A plot's Building Supply Box status by its own {@code constructionBoxId} -- added 2026-09-30
     * for the Plot Config Sign's own "Relocate" flow's plot-bounds validation. Centralizes the raw
     * {@code Blueprynts.*} facade calls here rather than in each caller, matching this class's own
     * existing "everything Blueprynts-referencing goes behind this bridge" boundary.
     */
    public static Optional<Blueprynts.ConstructionBoxStatus> resolveStatus(ServerLevel level, UUID constructionBoxId) {
        return Blueprynts.getConstructionBoxPos(level, constructionBoxId)
                .flatMap(pos -> Blueprynts.getConstructionBoxStatus(level, pos));
    }

    /** A plot's current Construction Box Tier -- added 2026-10-05 for {@code zone.ShopSeeding}, which needs to know how far up a Zone Type's seed catalog it's allowed to reach. */
    public static Optional<Integer> resolveTier(ServerLevel level, UUID constructionBoxId) {
        return Blueprynts.getConstructionBoxPos(level, constructionBoxId)
                .flatMap(pos -> Blueprynts.getConstructionBoxTier(level, pos));
    }

    /**
     * Pushes a plot's newly-raised Tier cap onto its Construction Box -- added 2026-10-09 for
     * "Upgrade Plot" ({@code construction.PlotTierUpgradeFunding}), called right after {@code
     * zone.PlotRecord#tier} is raised. Thin pass-through to {@link Blueprynts#setConstructionBoxAllowedTier}
     * -- see that method's own doc. No-op (silently) if this plot has no Construction Box at all.
     */
    public static void setAllowedTier(ServerLevel level, UUID constructionBoxId, int tier) {
        Blueprynts.getConstructionBoxPos(level, constructionBoxId)
                .ifPresent(pos -> Blueprynts.setConstructionBoxAllowedTier(level, pos, tier));
    }

    /** The bounding box of every BLUE cell in {@code grid} -- {@code null} if somehow none exist (shouldn't happen given callers' own {@code hasValidArea} precondition). */
    private static BuildableArea blueBoundingBox(PlotValidity.Grid grid, int minX, int minZ, int maxX, int maxZ) {
        int blueMinX = Integer.MAX_VALUE, blueMaxX = Integer.MIN_VALUE, blueMinZ = Integer.MAX_VALUE, blueMaxZ = Integer.MIN_VALUE;
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                if (grid.at(x, z) != PlotValidity.Cell.BLUE) {
                    continue;
                }
                blueMinX = Math.min(blueMinX, x);
                blueMaxX = Math.max(blueMaxX, x);
                blueMinZ = Math.min(blueMinZ, z);
                blueMaxZ = Math.max(blueMaxZ, z);
            }
        }
        return blueMaxX < blueMinX ? null : new BuildableArea(blueMinX, blueMinZ, blueMaxX, blueMaxZ);
    }

    /**
     * The real per-cell interior shape (GREEN or BLUE -- i.e. the whole plot, not just the buildable
     * BLUE region) as a {@link PlotArea} -- passed to {@code Blueprynts#createConstructionBox} so a
     * "Reposition Supply Box" can be constrained to the full plot instead of only the building's own
     * 15x15/50x50 footprint (user request, 2026-09-29), and -- unlike a bounding-rectangle reduction
     * -- without including real-world area the player never actually staked (user correction, same
     * day: "but a plot is a polygon, not a rectangle"). {@code null} if somehow no interior cells
     * exist (shouldn't happen given callers' own {@code hasValidArea} precondition).
     */
    private static PlotArea plotInteriorArea(PlotValidity.Grid grid, int minX, int minZ, int maxX, int maxZ) {
        int w = maxX - minX + 1;
        int h = maxZ - minZ + 1;
        boolean[] interior = new boolean[w * h];
        boolean any = false;
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                PlotValidity.Cell cell = grid.at(x, z);
                if (cell != PlotValidity.Cell.BLUE && cell != PlotValidity.Cell.GREEN) {
                    continue;
                }
                interior[(z - minZ) * w + (x - minX)] = true;
                any = true;
            }
        }
        return any ? new PlotArea(minX, minZ, w, h, interior) : null;
    }
}
