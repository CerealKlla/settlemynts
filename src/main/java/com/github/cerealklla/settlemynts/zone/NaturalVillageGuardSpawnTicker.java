package com.github.cerealklla.settlemynts.zone;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.github.cerealklla.cartographyr.api.Cartography;
import com.github.cerealklla.cartographyr.geo.EntityId;
import com.github.cerealklla.cartographyr.geo.GeographicEntity;
import com.github.cerealklla.cartographyr.geo.Geometry;
import com.github.cerealklla.settlemynts.guardhouse.GuardEntity;
import com.github.cerealklla.settlemynts.registration.ModEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Automatically keeps every naturally-discovered village garrisoned at a flat population ratio --
 * explicit user spec, 2026-11-16: "1 guard for every 5 plots," rounding up with a guaranteed
 * minimum of 1 for any village with at least one plot (confirmed with the user: "Ceiling, minimum
 * 1" -- 3 plots -> 1 guard, 7 -> 2, 12 -> 3). No Guardhouse, Guard Kit Stand, Kyt, or box economy is
 * involved at all -- none of that infrastructure exists for a natural village, and none is needed
 * here; every spawned guard gets the exact same fixed starter kit directly ({@link
 * GuardEntity#equipFixedNaturalGuardGear}).
 *
 * <p>Same cheap periodic full-settlement scan shape as {@link NaturalVillageShopLinkTicker}/{@link
 * ShopMidnightRestockTicker} -- no persisted "how many guards does this village currently have"
 * bookkeeping at all; every scan just counts how many already-alive {@link GuardEntity} instances
 * inside the settlement's own Cartographyr polygon carry this settlement's {@link SettlementKey}
 * (see {@link GuardEntity#naturalSettlementKey()}), and tops up the deficit. A dead guard simply
 * isn't counted on the next scan and gets silently replaced -- the same self-healing "presence
 * check, not a one-shot spawn" idiom {@code guardhouse.GuardSpawnTicker}/{@code
 * resident.ResidentSpawnTicker} already use, just population-ratio-driven instead of
 * one-slot-per-stand/sign.
 *
 * <p><b>Patrol area</b>: a natural-village guard has no owning plot or Guardhouse to patrol, and no
 * real {@code roadway.RoadwayStakeEntity} network either (that's player-settlement infrastructure
 * only, since it needs an actual Town Planner to place it) -- {@link GuardEntity#resolvePatrolArea}
 * resolves straight to the settlement's own whole Cartographyr polygon for a guard carrying a {@link
 * SettlementKey}, so {@code guardhouse.GuardPatrolAreaGoal}'s road-route attempt naturally comes back
 * empty and falls through to its own natural-village fallback instead -- which, as of 2026-11-17,
 * picks from {@link NaturalRoadStakes}' per-plot waypoints rather than a blind random point anywhere
 * in that whole polygon (see that class's own doc for the real "guards stranded at the bottom of a
 * cliff" bug this fixes). This ticker's own spawn-position picker uses the same waypoints.
 *
 * <p><b>Combat style</b>, explicit user spec after confirming no Guard in this suite -- and no
 * vanilla mob at all -- ever runs melee and ranged simultaneously or switches between them live:
 * one guard, one fixed weapon, same as every other Guard here and every vanilla bow-mob (Skeleton,
 * Stray, ...). See {@link GuardEntity#equipFixedNaturalGuardGear}'s own doc for the actual gear.
 */
public final class NaturalVillageGuardSpawnTicker {

    private static final int GUARDS_PER_PLOTS = 5;
    private static final int SCAN_INTERVAL_TICKS = 100; // 5 seconds, same cadence as every other full-settlement scan ticker in this mod.
    // Staggered against this mod's other 100-tick full-world scan tickers -- see
    // farm.FarmerSpawnTicker's own doc for the full "thundering herd" root cause this avoids.
    private static final int TICK_OFFSET = 88;

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if ((server.getTickCount() + TICK_OFFSET) % SCAN_INTERVAL_TICKS != 0) {
            return;
        }
        // Villages are overworld-only, same assumption NaturalVillageShopLinkTicker already makes.
        ServerLevel level = server.overworld();
        if (level == null) {
            return;
        }
        for (Map.Entry<SettlementKey, List<PlotRecord>> entry : NaturalSettlementPlotStore.get(server).all().entrySet()) {
            maybeSpawnGuards(level, entry.getKey(), entry.getValue());
        }
    }

    private void maybeSpawnGuards(ServerLevel level, SettlementKey settlement, List<PlotRecord> plots) {
        if (plots.isEmpty()) {
            return;
        }
        Optional<GeographicEntity> settlementEntity = Cartography.getEntity(level, new EntityId(settlement.settlementEntityId()));
        if (settlementEntity.isEmpty() || !(settlementEntity.get().geometry() instanceof Geometry.Polygon polygon) || polygon.vertices().isEmpty()) {
            return;
        }
        int target = (plots.size() + GUARDS_PER_PLOTS - 1) / GUARDS_PER_PLOTS; // Ceiling; always >= 1 since plots is non-empty here.

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (Geometry.Polygon.Vertex vertex : polygon.vertices()) {
            minX = Math.min(minX, vertex.x());
            maxX = Math.max(maxX, vertex.x());
            minZ = Math.min(minZ, vertex.z());
            maxZ = Math.max(maxZ, vertex.z());
        }
        // Real bug fix, 2026-11-17 -- real report: "Ashallow seems to have had an issue and spawned
        // an insane amount of npc guards." Root cause: `alive` below only ever counts currently-
        // loaded GuardEntity instances (getEntitiesOfClass can't see into an unloaded chunk), but
        // this scan runs every 5 seconds for EVERY known natural settlement unconditionally, whether
        // or not anyone is anywhere near it. A settlement with nobody nearby reads `alive == 0`
        // forever (its real, already-spawned guards are simply unloaded, not gone) and this ticker
        // topped it back up to `target` on every single pass -- `addFreshEntity` happily materializes
        // a new entity into an unloaded chunk regardless, so guards accumulated without bound the
        // entire time nobody visited. Skip the whole settlement this pass if its own area isn't
        // actually loaded -- the same "chunk not loaded -- skip this scan" rule
        // `guardhouse.GuardSpawnTicker#fillStand` already follows for exactly this reason.
        BlockPos center = new BlockPos((minX + maxX) / 2, level.getSeaLevel(), (minZ + maxZ) / 2);
        if (!level.isLoaded(center)) {
            return;
        }
        AABB bounds = new AABB(minX, level.getMinY(), minZ, maxX, level.getMaxY(), maxZ);
        int alive = level.getEntitiesOfClass(GuardEntity.class, bounds, g -> settlement.equals(g.naturalSettlementKey())).size();
        if (alive >= target) {
            return;
        }
        // Real bug fix, 2026-11-17 -- see NaturalRoadStakes' own doc for the full writeup. Used to
        // sample a blind random (x, z) across the settlement's whole bounding polygon; on a cliff-
        // sprawling village that's exactly as likely to land at the base of a cliff as anywhere a
        // guard could actually patrol to from there, stranding it the instant it tried to move.
        List<BlockPos> stakes = NaturalRoadStakes.resolve(level, plots);
        if (stakes.isEmpty()) {
            return; // No resolvable stake this scan -- try again next pass rather than guessing.
        }
        for (int i = alive; i < target; i++) {
            BlockPos spawnPos = stakes.get(level.getRandom().nextInt(stakes.size()));
            spawnGuard(level, settlement, spawnPos);
        }
    }

    private void spawnGuard(ServerLevel level, SettlementKey settlement, BlockPos spawnPos) {
        GuardEntity guard = new GuardEntity(ModEntities.GUARD.get(), level);
        guard.setPos(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
        guard.setNaturalSettlementIdentity(settlement);
        guard.equipFixedNaturalGuardGear();
        guard.setHomePos(spawnPos);
        guard.setHomeDimension(level.dimension());
        level.addFreshEntity(guard);
    }
}
