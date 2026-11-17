package com.github.cerealklla.settlemynts.resident;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * Vacates whichever bed slot (if any) a plain vanilla {@link Villager} was bound to when it dies --
 * see {@link BedSpawnSlot}'s own doc for the full 2026-11-17 redesign writeup this replaces (the old
 * "respawn at the exact death position, if within some margin of the settlement" queue). A villager
 * that was never swept into the bed-slot system (e.g. one that died before {@link
 * VillagerBedSpawnTicker} ever discovered its bed) simply has nothing to vacate -- no respawn is
 * lost that wasn't already going to happen on its own once that bed gets discovered.
 *
 * <p>Deliberately only ever matches a real vanilla {@code Villager}, not Settlemynts' own {@code
 * ResidentVillagerEntity} subclass (which already has its own, unrelated respawn mechanism via
 * {@code ResidentSpawnTicker}) -- the explicit {@code getClass() == Villager.class} check (rather
 * than a plain {@code instanceof}) is what excludes that subclass. See {@link
 * VillagerConversionListener} for the sibling case where a villager is converted to a Zombie
 * Villager instead of dying outright.
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
        BedSpawnSlotIndex index = BedSpawnSlotIndex.get(level.getServer());
        index.findByVillager(event.getEntity().getUUID())
                .ifPresent(slot -> index.put(slot.vacated(level.getGameTime() + VillagerBedSpawnTicker.RESPAWN_DELAY_TICKS)));
    }
}
