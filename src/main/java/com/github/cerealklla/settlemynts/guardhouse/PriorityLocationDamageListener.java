package com.github.cerealklla.settlemynts.guardhouse;

import java.util.UUID;

import com.github.cerealklla.settlemynts.api.Settlemynts;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * The second of the two Priority Location triggers -- explicit user spec: "Any other NPC when
 * taking damage creates one as well, and each time they take damage it's escalated again." The
 * location is placed at the *damaged* NPC's own position, not the attacker's. Deliberately no dedup
 * here (unlike the sight-triggered path's per-mob dedup, see {@link PriorityLocation#registerSeenMob})
 * -- every real damage instance escalates, even several in a row from the same single attacker, per
 * the user's own wording ("each time").
 *
 * <p>Scoped to real settlement NPCs only (Guards, plot workers, a still-seated Tavern patron, a
 * tagged natural-village Villager) via {@link Settlemynts#findPlotIdForEntity} -- a player taking
 * damage never matches (explicit spec says "NPC"), and a summoned Tavern Companion (which follows
 * its owner anywhere, potentially nowhere near any settlement at all) is correctly excluded too,
 * since {@code findPlotIdForEntity} only recognizes a Tavern NPC while it's still the stationary,
 * never-hired patron sitting on its stool.
 */
public final class PriorityLocationDamageListener {

    @SubscribeEvent
    public void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (event.getAmount() <= 0 || !(event.getEntity().level() instanceof ServerLevel level)) {
            return;
        }
        Entity victim = event.getEntity();
        UUID settlementCoreId = resolveSettlementCoreId(level, victim);
        if (settlementCoreId != null) {
            PriorityLocationRegistry.registerDamage(level, settlementCoreId, victim.blockPosition());
        }
    }

    private static UUID resolveSettlementCoreId(ServerLevel level, Entity victim) {
        if (victim instanceof GuardEntity guard) {
            return guard.getSettlementCoreId();
        }
        return Settlemynts.findPlotIdForEntity(victim)
                .flatMap(plotId -> Settlemynts.findSettlementCoreIdForPlot(level, plotId))
                .orElse(null);
    }
}
