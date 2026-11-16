package com.github.cerealklla.settlemynts.resident;

import com.github.cerealklla.cartographyr.api.Cartography;
import com.github.cerealklla.cartographyr.geo.Classification;
import com.github.cerealklla.cartographyr.geo.EntityType;
import com.github.cerealklla.cartographyr.geo.GeographicEntity;
import com.github.cerealklla.cartographyr.geo.Geometry;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Shared "queue a replacement villager" resolution used by both {@link VillagerDeathListener} (a
 * real death) and {@link VillagerConversionListener} (converted to a Zombie Villager instead of
 * dying outright -- just as much a population loss, see that class's own doc). Matches against the
 * whole settlement's own bounding polygon with a margin, not an exact {@code contains} check --
 * real gap found 2026-11-16 investigating a natural village left with zero residents: a villager
 * killed while fleeing even a few blocks past the settlement's own registered edge (easy during an
 * actual attack) was never queued for respawn at all, permanently losing that resident.
 * {@link #OUTSIDE_SETTLEMENT_MARGIN_BLOCKS} matches {@code guardhouse.PriorityLocationRegistry}'s
 * own cluster radius, for the same "close enough to still count as this settlement" reasoning.
 */
final class VillagerRespawnQueue {

    static final double OUTSIDE_SETTLEMENT_MARGIN_BLOCKS = 20.0;
    private static final long RESPAWN_DELAY_TICKS = 5L * 60L * 20L; // 5 minutes.

    private VillagerRespawnQueue() {
    }

    static void queueIfInSettlement(ServerLevel level, BlockPos pos) {
        for (GeographicEntity entity : Cartography.findEntities(level, Classification.CONSTRUCTED)) {
            if (!entity.type().equals(EntityType.SETTLEMENT)) {
                continue;
            }
            boolean withinMargin = entity.geometry() instanceof Geometry.Polygon polygon
                    ? polygon.distanceOutside(pos.getX(), pos.getZ()) <= OUTSIDE_SETTLEMENT_MARGIN_BLOCKS
                    : entity.geometry().contains(pos.getX(), pos.getZ());
            if (!withinMargin) {
                continue;
            }
            PendingVillagerRespawnIndex.get(level.getServer()).add(new PendingVillagerRespawn(
                    entity.id().value(), pos, level.dimension().identifier(),
                    level.getGameTime() + RESPAWN_DELAY_TICKS));
            return;
        }
    }
}
