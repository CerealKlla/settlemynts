package com.github.cerealklla.settlemynts.watchtower;

import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * Clears any real {@code Blocks.LIGHT} position a Watch Tower Beacon queued for removal while its
 * chunk was unloaded (see {@link WatchTowerPendingLightRemoval}) the moment that chunk actually
 * loads again -- the safety net {@code founding.GhostBoundaryWallEntity}'s own entity-count incident
 * (this same session) didn't have, built in here from the start instead of reactively.
 */
public final class WatchTowerChunkLoadListener {

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        for (GlobalPos pos : WatchTowerPendingLightRemoval.get(level.getServer()).drain(level.dimension(), event.getChunk().getPos())) {
            BlockState state = level.getBlockState(pos.pos());
            if (state.is(Blocks.LIGHT)) {
                level.setBlock(pos.pos(), Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }
}
