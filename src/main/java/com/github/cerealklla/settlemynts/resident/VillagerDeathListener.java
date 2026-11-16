package com.github.cerealklla.settlemynts.resident;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * Records a {@link PendingVillagerRespawn} whenever a plain vanilla {@link Villager} dies inside
 * (or within {@link VillagerRespawnQueue#OUTSIDE_SETTLEMENT_MARGIN_BLOCKS} of) a known settlement's
 * footprint -- Part D of the "Natural settlements..." plan, 2026-10-08. Deliberately only ever
 * matches a real vanilla {@code Villager}, not Settlemynts' own {@code ResidentVillagerEntity}
 * subclass (which already has its own, unrelated respawn mechanism via {@code
 * ResidentSpawnTicker}) -- the explicit {@code getClass() == Villager.class} check (rather than a
 * plain {@code instanceof}) is what excludes that subclass. See {@link VillagerConversionListener}
 * for the sibling case where a villager is converted to a Zombie Villager instead of dying outright.
 */
public final class VillagerDeathListener {

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity().getClass() != Villager.class) {
            return;
        }
        if (!(event.getEntity().level() instanceof ServerLevel level)) {
            return;
        }
        VillagerRespawnQueue.queueIfInSettlement(level, event.getEntity().blockPosition());
    }
}
