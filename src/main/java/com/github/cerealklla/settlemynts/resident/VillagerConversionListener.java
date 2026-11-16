package com.github.cerealklla.settlemynts.resident;

import java.util.Optional;
import java.util.UUID;

import com.github.cerealklla.settlemynts.api.Settlemynts;
import com.github.cerealklla.settlemynts.guardhouse.PriorityLocationRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingConversionEvent;

/**
 * A zombie converting a villager instead of killing it outright is just as much a population loss
 * as a real death -- real gap found 2026-11-16, investigating a natural village left with zero
 * remaining residents: {@link VillagerDeathListener} only ever fires on {@code LivingDeathEvent},
 * which a conversion never raises at all (vanilla replaces the entity in place -- confirmed against
 * the decompiled source, {@code Zombie#killedEntity}/{@code Villager} -- no death pipeline runs).
 *
 * <p>Also plants a Priority Location centered on the new Zombie Villager, explicit user spec: "when
 * a villager becomes a zombie, stick a priority location marker centered on that villager until
 * they die." No custom until-it-dies tracking is needed -- the Zombie Villager is itself a real
 * {@code Enemy}, so {@code guardhouse.PriorityLocationCleanupTicker}'s existing "no hostile left in
 * range" sweep already removes the marker the instant that specific zombie actually dies (or is
 * cured back into a villager), the same way it already handles every other hostile.
 *
 * <p>Same {@code getClass() == Villager.class} scoping as {@link VillagerDeathListener} -- a {@code
 * ResidentVillagerEntity} can never reach this listener at all, since {@code
 * ResidentConversionGuard} cancels its own conversion outright before {@code Post} would ever fire
 * for one.
 */
public final class VillagerConversionListener {

    @SubscribeEvent
    public void onConversion(LivingConversionEvent.Post event) {
        if (event.getEntity().getClass() != Villager.class) {
            return;
        }
        if (!(event.getOutcome() instanceof ZombieVillager zombie)) {
            return;
        }
        if (!(zombie.level() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = zombie.blockPosition();
        VillagerRespawnQueue.queueIfInSettlement(level, pos);

        Optional<UUID> settlementCoreId = Settlemynts.findSettlementCoreIdNear(
                level, pos, VillagerRespawnQueue.OUTSIDE_SETTLEMENT_MARGIN_BLOCKS);
        settlementCoreId.ifPresent(coreId ->
                PriorityLocationRegistry.registerSighting(level, coreId, pos, zombie.getUUID()));
    }
}
