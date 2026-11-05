package com.github.cerealklla.settlemynts.npc;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Births a {@link NpcRole#WANDERING} identity for every real vanilla {@link WanderingTrader} the
 * instant it's first seen -- moved here from Audyble's own {@code npc.WanderingIdentityListener},
 * 2026-11-05, per the user's explicit direction that Settlemynts (not Audyble) should manage NPC
 * history even though this mod never spawns Wandering Traders itself (see {@link NpcIdentityStore}'s
 * own class doc for the full ownership-move writeup). Keying by the trader's own stable entity UUID
 * means "first sighting of this UUID" is a correct, side-effect-free birth trigger regardless of
 * whether this is a genuinely fresh spawn or just a chunk reload of a trader that's existed for days
 * -- {@link NpcIdentityBirth#resolveOrBeginBirth} is itself idempotent (a fast-path lookup on every
 * call after the first), so no separate "is this really new" check is needed here at all.
 */
public final class WanderingIdentityListener {

    @SubscribeEvent
    public void onJoinLevel(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof WanderingTrader trader)) {
            return;
        }
        NpcIdentityBirth.resolveOrBeginBirth(level, NpcIdentityKey.wandering(trader.getUUID()), null, "");
    }
}
