package com.github.cerealklla.settlemynts.resident;

import com.github.cerealklla.cartographyr.api.Cartography;
import com.github.cerealklla.cartographyr.geo.Classification;
import com.github.cerealklla.cartographyr.geo.EntityType;
import com.github.cerealklla.cartographyr.geo.GeographicEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * Records a {@link PendingVillagerRespawn} whenever a plain vanilla {@link Villager} dies inside a
 * known natural settlement's footprint -- Part D of the "Natural settlements..." plan, 2026-10-08.
 * Deliberately only ever matches a real vanilla {@code Villager}, not Settlemynts' own {@code
 * ResidentVillagerEntity} subclass (which already has its own, unrelated respawn mechanism via
 * {@code ResidentSpawnTicker}) -- the explicit {@code getClass() == Villager.class} check (rather
 * than a plain {@code instanceof}) is what excludes that subclass.
 */
public final class VillagerDeathListener {

    private static final long RESPAWN_DELAY_TICKS = 5L * 60L * 20L; // 5 minutes.

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity().getClass() != Villager.class) {
            return;
        }
        if (!(event.getEntity().level() instanceof ServerLevel level)) {
            return;
        }
        Villager villager = (Villager) event.getEntity();
        BlockPos pos = villager.blockPosition();

        for (GeographicEntity entity : Cartography.findEntities(level, Classification.CONSTRUCTED)) {
            if (!entity.type().equals(EntityType.SETTLEMENT) || !entity.geometry().contains(pos.getX(), pos.getZ())) {
                continue;
            }
            PendingVillagerRespawnIndex.get(level.getServer()).add(new PendingVillagerRespawn(
                    entity.id().value(), pos, level.dimension().identifier(),
                    level.getGameTime() + RESPAWN_DELAY_TICKS));
            return;
        }
    }
}
