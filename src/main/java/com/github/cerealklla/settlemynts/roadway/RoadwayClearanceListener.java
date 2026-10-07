package com.github.cerealklla.settlemynts.roadway;

import com.github.cerealklla.settlemynts.registration.ModBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Enforces "nothing can be built... to impede the 4 blocks above the surface of the roadway"
 * (Roadways Milestone 1, added 2026-10-06) -- a small, direct listener, not routed through
 * Protectyons' {@code ProtectionLevel} system (unlike settlement/plot protection), since this is a
 * simple fixed-height check against the block directly below, not a polygon/entity lookup.
 */
public final class RoadwayClearanceListener {

    private static final int CLEARANCE_BLOCKS = 4;

    @SubscribeEvent
    public void onEntityPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = event.getPos();
        for (int i = 1; i <= CLEARANCE_BLOCKS; i++) {
            if (level.getBlockState(pos.below(i)).is(ModBlocks.ROADWAY.get())) {
                event.setCanceled(true);
                return;
            }
        }
    }
}
