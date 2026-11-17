package com.github.cerealklla.settlemynts.zone;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.github.cerealklla.cartographyr.api.Cartography;
import com.github.cerealklla.cartographyr.geo.EntityId;
import com.github.cerealklla.cartographyr.geo.GeographicEntity;
import com.github.cerealklla.cartographyr.geo.Geometry;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * A natural village's own set of real, walkable waypoints -- one per plot, at that plot's own
 * Cartographyr polygon centroid snapped to the real terrain surface there. Explicit user design,
 * 2026-11-17: "spawn roadway stakes along the naturally generated roads in the town so [guards]
 * have targeted waypoints so they can know when they are stuck... The NPC settlement guards could
 * use those invisible road stakes as their spawn points since those settlements won't have guard
 * manequins."
 *
 * <p>No new entity or persisted registry at all -- unlike a player settlement's real, player-placed
 * {@code roadway.RoadwayStakeEntity} graph (which needs persistence because a player actually built
 * it), these are cheap to recompute on demand straight from {@code NaturalSettlementPlotStore} +
 * each plot's own already-registered Cartographyr polygon, the exact same per-plot anchor technique
 * {@code resident.VillagerBedSpawnTicker} already uses to discover beds. Robust to a cliff-sprawling
 * village for the same reason that technique is: each stake is anchored to its OWN plot's real
 * terrain, not one blind random sample across the settlement's whole bounding box.
 *
 * <p><b>This is the actual root cause fix</b> for a real report the same day: natural-village guards
 * getting permanently stranded at the bottom of a cliff. {@code NaturalVillageGuardSpawnTicker}'s own
 * spawn-position picker and {@code guardhouse.GuardPatrolAreaGoal}'s natural-village fallback target
 * picker both used to sample ANY {@code (x, z)} inside the settlement's whole bounding polygon and
 * snap it to whatever terrain height happened to be there, with zero regard for whether that spot is
 * actually reachable on foot from wherever the guard currently is -- on a cliff-sprawling village,
 * that's exactly as likely to land at the base of a cliff face as anywhere a guard could actually
 * walk to. Both now pick from this bounded, per-building set of waypoints instead. Combined with the
 * existing {@code npc.NpcStuckWatchdog} (already wired into every custom ground NPC including {@code
 * guardhouse.GuardEntity}) teleporting a stuck guard back to its own {@code homePos} -- once that
 * home position is itself always a real stake (see the spawn-ticker change), that existing rescue
 * mechanism now always has a genuinely reachable destination to fall back to, with no new stuck-
 * detection code needed at all.
 */
public final class NaturalRoadStakes {

    private NaturalRoadStakes() {
    }

    public static List<BlockPos> resolve(ServerLevel level, List<PlotRecord> plots) {
        List<BlockPos> stakes = new ArrayList<>();
        for (PlotRecord plot : plots) {
            Optional<GeographicEntity> plotEntity = Cartography.getEntity(level, new EntityId(plot.cartographyrPlotEntityId()));
            if (plotEntity.isEmpty() || !(plotEntity.get().geometry() instanceof Geometry.Polygon polygon) || polygon.vertices().isEmpty()) {
                continue; // Not loaded/resolvable this scan -- callers already treat an empty/short list as "try again later."
            }
            long sumX = 0, sumZ = 0;
            for (Geometry.Polygon.Vertex vertex : polygon.vertices()) {
                sumX += vertex.x();
                sumZ += vertex.z();
            }
            int x = (int) (sumX / polygon.vertices().size());
            int z = (int) (sumZ / polygon.vertices().size());
            if (!level.hasChunk(x >> 4, z >> 4)) {
                continue; // Chunk not loaded -- skip until it is, same as every other ticker in this mod.
            }
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            stakes.add(new BlockPos(x, y, z));
        }
        return stakes;
    }
}
