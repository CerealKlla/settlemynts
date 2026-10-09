package com.github.cerealklla.settlemynts.roadway;

import com.github.cerealklla.settlemynts.bridge.BlueprintsConstructionBridge;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.zone.PlotRecord;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;

/**
 * Resolves "what Tier should this settlement's roads currently look like" (added 2026-10-09,
 * explicit request: "a visual progression which is directly tied to a settlement's town hall
 * tier... no town hall/T1 Town Hall should use a dirt road... when the Town Hall is upgraded all
 * the roads will upgrade with it"). Mirrors every other "read a plot's Construction Box Tier"
 * call site in this mod (e.g. Guardhouse garrison caps) -- the bound Blueprint's own Tier, via
 * {@link BlueprintsConstructionBridge#resolveTier}, not whether construction has actually
 * *completed* (same reasoning/precedent as those other call sites: once a higher Tier is bound,
 * the plot is treated as that Tier already). Defaults to Tier 1 (dirt) whenever there's no Town
 * Hall plot yet, no Construction Box, or no Blueprint bound -- same "no Town Hall" case the user
 * explicitly called out.
 */
public final class RoadwayTierResolver {

    private RoadwayTierResolver() {
    }

    public static int currentTier(ServerLevel level, GhostTownHallCoreEntity core) {
        PlotRecord townHall = core.getPlots().stream()
                .filter(p -> GhostTownHallCoreEntity.TOWN_HALL_ZONE_TYPE_ID.equals(p.zoneTypeId()))
                .findFirst().orElse(null);
        if (townHall == null || !ModList.get().isLoaded("blueprynts") || townHall.constructionBoxId().isEmpty()) {
            return 1;
        }
        return BlueprintsConstructionBridge.resolveTier(level, townHall.constructionBoxId().get()).orElse(1);
    }
}
