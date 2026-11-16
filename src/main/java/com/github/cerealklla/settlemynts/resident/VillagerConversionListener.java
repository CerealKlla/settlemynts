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
 * they die" -- and, same-day follow-up, "the 'until it dies' was directly tied to 'centered on the
 * zombie,' meaning if the zombie is moving so is the location, so it can never walk out of the area
 * and despawn." Uses {@code guardhouse.PriorityLocationRegistry#registerTrackedThreat} rather than
 * the plain sighting/damage paths -- that location follows this exact zombie's live position every
 * cleanup pass and is only removed once this exact entity can no longer be resolved alive, instead
 * of the generic "no hostile left in a fixed radius" rule every other trigger uses.
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
                PriorityLocationRegistry.registerTrackedThreat(level, coreId, zombie.getUUID(), pos));
    }
}
