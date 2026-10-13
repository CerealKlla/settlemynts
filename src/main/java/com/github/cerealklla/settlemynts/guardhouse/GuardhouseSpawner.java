package com.github.cerealklla.settlemynts.guardhouse;

import java.util.Optional;
import java.util.UUID;

import com.github.cerealklla.settlemynts.registration.ModBlocks;
import com.github.cerealklla.settlemynts.zone.PlotSitePlacement;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;

/**
 * Spawns a Guardhouse-typed plot's own structure at Finalize time -- only called for a plot whose
 * {@code zoneTypeId} equals {@link GuardhouseConstants#GUARDHOUSE_ZONE_TYPE_ID}, right alongside
 * {@code plotsign.PlotConfigSignSpawner}. Lands two cells laterally beside the same site the
 * Building Supply Box/Plot Config Sign anchor to (one cell further than the sign, so the three never
 * collide), same facing convention.
 *
 * <p><b>Idempotent, 2026-10-13</b> -- real live report: one settlement had 10 living guards tracked
 * under a single Guardhouse plot, above even the absolute {@link GuardhouseConstants#MAX_GARRISON_SIZE}
 * (8). {@code finalizePlot} already guards against two *different* plot sessions overlapping, but
 * nothing stopped this method itself from running twice for the *same* {@code plotId} (e.g. a
 * double-submitted Finalize click/network retry) -- a second call would {@code setBlock} a second,
 * brand-new {@link GuardhouseBlockEntity} at the same position (discarding the first's own {@code
 * GarrisonGuardIds} tracking in the process) and re-point {@link GuardhouseIndex} at it, silently
 * orphaning the first Guardhouse's already-spawned guards forever -- `GuardSpawnTicker` then filled
 * the new, blank-tracking instance's garrison right back up from scratch on top of them. Now a no-op
 * if this {@code plotId} already has a real, resolvable Guardhouse registered.
 */
public final class GuardhouseSpawner {

    private GuardhouseSpawner() {
    }

    public static void spawn(ServerLevel level, UUID settlementCoreId, UUID plotId, PlotSitePlacement.Site site) {
        GuardhouseIndex index = GuardhouseIndex.get(level.getServer());
        Optional<GlobalPos> existing = index.get(plotId);
        if (existing.isPresent() && existing.get().dimension().equals(level.dimension())
                && level.getBlockEntity(existing.get().pos()) instanceof GuardhouseBlockEntity) {
            return; // Already has a real Guardhouse -- see this class's own doc for why this matters.
        }

        Direction lateral = site.towardBlue().getClockWise();
        BlockPos guardhousePos = site.pos().relative(lateral, 2);
        Direction facing = site.towardBlue().getOpposite();

        level.setBlock(guardhousePos, ModBlocks.GUARDHOUSE.get().defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING, facing), 3);
        if (level.getBlockEntity(guardhousePos) instanceof GuardhouseBlockEntity guardhouse) {
            guardhouse.setIdentity(settlementCoreId, plotId);
        }
        index.put(plotId, GlobalPos.of(level.dimension(), guardhousePos));
    }
}
