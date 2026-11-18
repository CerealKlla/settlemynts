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
 * <p><b>Bed-slot vacating, 2026-11-17 redesign</b> -- see {@link BedSpawnSlot}'s own doc for the full
 * writeup. If the converting villager was bound to a bed, that slot is vacated here (starting its
 * respawn timer) exactly like a real death; a not-yet-bed-bound villager (one that hasn't been swept
 * into the new system by {@link VillagerBedSpawnTicker} yet) simply has nothing to vacate, same as
 * before this redesign.
 *
 * <p>Also plants a Priority Location centered on the new Zombie Villager, explicit user spec: "when
 * a villager becomes a zombie, stick a priority location marker centered on that villager until
 * they die" -- and, same-day follow-up, "the 'until it dies' was directly tied to 'centered on the
 * zombie,' meaning if the zombie is moving so is the location, so it can never walk out of the area
 * and despawn." Uses {@code guardhouse.PriorityLocationRegistry#registerTrackedThreat} rather than
 * the plain sighting/damage paths -- that location follows this exact zombie's live position every
 * cleanup pass and is only removed once this exact entity can no longer be resolved alive, instead
 * of the generic "no hostile left in a fixed radius" rule every other trigger uses. Kept entirely
 * independent of the bed-slot system above -- a settlement-proximity match (not "was this a tracked
 * resident") is still the right scope for an alert the whole settlement's guards should respond to.
 *
 * <p>Same {@code getClass() == Villager.class} scoping as {@link VillagerDeathListener} -- a {@code
 * ResidentVillagerEntity} (a distinct subclass) never reaches this listener either way, since this
 * only matches the exact base {@code Villager} class. A converted Resident gets no bed-slot-vacate
 * or Priority Location handling as a result -- not yet covered, since zombies converting a
 * settlement's own residents is intended to be a real threat guards are meant to stop, not something
 * this mod should paper over after the fact (removed 2026-11-18, see {@code
 * resident.ResidentVillagerEntity}'s own former guard class, now deleted).
 */
public final class VillagerConversionListener {

    /** Same "close enough to still count as this settlement" margin {@code guardhouse.PriorityLocationRegistry}'s own cluster radius uses -- independent of the bed-slot system's own, unrelated discovery radius. */
    private static final double PRIORITY_LOCATION_SETTLEMENT_MARGIN_BLOCKS = 20.0;

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
        BedSpawnSlotIndex.get(level.getServer()).findByVillager(event.getEntity().getUUID())
                .ifPresent(slot -> BedSpawnSlotIndex.get(level.getServer())
                        .put(slot.vacated(level.getGameTime() + VillagerBedSpawnTicker.RESPAWN_DELAY_TICKS)));

        BlockPos pos = zombie.blockPosition();
        Optional<UUID> settlementCoreId = Settlemynts.findSettlementCoreIdNear(
                level, pos, PRIORITY_LOCATION_SETTLEMENT_MARGIN_BLOCKS);
        settlementCoreId.ifPresent(coreId ->
                PriorityLocationRegistry.registerTrackedThreat(level, coreId, zombie.getUUID(), pos));
    }
}
