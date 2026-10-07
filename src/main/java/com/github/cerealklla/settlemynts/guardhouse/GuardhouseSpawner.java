package com.github.cerealklla.settlemynts.guardhouse;

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
 */
public final class GuardhouseSpawner {

    private GuardhouseSpawner() {
    }

    public static void spawn(ServerLevel level, UUID settlementCoreId, UUID plotId, PlotSitePlacement.Site site) {
        Direction lateral = site.towardBlue().getClockWise();
        BlockPos guardhousePos = site.pos().relative(lateral, 2);
        Direction facing = site.towardBlue().getOpposite();

        level.setBlock(guardhousePos, ModBlocks.GUARDHOUSE.get().defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING, facing), 3);
        if (level.getBlockEntity(guardhousePos) instanceof GuardhouseBlockEntity guardhouse) {
            guardhouse.setIdentity(settlementCoreId, plotId);
        }
        GuardhouseIndex.get(level.getServer()).put(plotId, GlobalPos.of(level.dimension(), guardhousePos));
    }
}
