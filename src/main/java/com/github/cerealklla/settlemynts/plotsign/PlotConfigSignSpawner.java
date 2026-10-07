package com.github.cerealklla.settlemynts.plotsign;

import java.util.UUID;

import com.github.cerealklla.settlemynts.registration.ModBlocks;
import com.github.cerealklla.settlemynts.zone.PlotSitePlacement;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;

/**
 * Spawns a plot's Plot Config Sign at Finalize time, unconditionally -- design doc Section 14a,
 * corrected 2026-09-30 (user): "the Plot Sign should spawn at the same time as the Construction Box
 * spawns, but next to the box, not on it... if a server doesn't have Blueprynts running there isn't
 * an inherent dependency. In that scenario the Construction Box would never even spawn, but we'd
 * still want the Plot Sign to exist." Called from {@code SettlemyntsMod#finalizePlot} regardless of
 * whether Blueprynts is loaded/a Building Supply Box was actually placed -- this class itself never
 * references anything Blueprynts-owned.
 */
public final class PlotConfigSignSpawner {

    private PlotConfigSignSpawner() {
    }

    /** {@code site} is the same {@link PlotSitePlacement.Site} the Building Supply Box (if any) anchors to -- the sign lands one cell laterally beside it, same facing convention. */
    public static void spawn(ServerLevel level, UUID settlementCoreId, UUID plotId, PlotSitePlacement.Site site) {
        Direction lateral = site.towardBlue().getClockWise();
        BlockPos signPos = site.pos().relative(lateral);
        Direction facing = site.towardBlue().getOpposite();

        level.setBlock(signPos, ModBlocks.PLOT_CONFIG_SIGN.get().defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING, facing), 3);
        if (level.getBlockEntity(signPos) instanceof PlotConfigSignBlockEntity sign) {
            sign.setIdentity(settlementCoreId, plotId);
        }
        PlotConfigSignIndex.get(level.getServer()).put(plotId, GlobalPos.of(level.dimension(), signPos));
    }
}
