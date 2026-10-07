package com.github.cerealklla.settlemynts.resident;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingConversionEvent;

/**
 * Prevents a {@link ResidentVillagerEntity} from ever converting to anything else (zombie-villager
 * via a zombie's attack, witch via a lightning strike, etc.) -- confirmed via the decompiled source
 * ({@code Zombie#killedEntity}, {@code Villager#thunderHit}) that both routes fire a cancellable
 * {@code LivingConversionEvent.Pre} before replacing the entity. Cancelling here still lets the
 * original attack's death pipeline run normally afterward (e.g. a zombie's killing blow still kills
 * the resident outright) -- only the *replacement* is prevented, which is exactly what's wanted:
 * see {@code ResidentSpawnTicker}'s own doc for why a normal death is actually the mechanism that
 * drives the 5-second free respawn.
 */
public final class ResidentConversionGuard {

    @SubscribeEvent
    public void onConversion(LivingConversionEvent.Pre event) {
        if (event.getEntity() instanceof ResidentVillagerEntity) {
            event.setCanceled(true);
        }
    }
}
