package com.github.cerealklla.settlemynts.npc;

import com.mojang.serialization.Codec;

/**
 * What kind of NPC an {@link NpcIdentityKey} identifies -- moved here from Audyble's own {@code
 * npc.PlotRole}, 2026-11-05, as part of moving persistent NPC identity/memory ownership from Audyble
 * to Settlemynts (see {@code NpcIdentityStore}'s own class doc for the full real-report writeup). The
 * four worker roles are keyed by their owning plot's own {@code zone.PlotRecord#plotId}; {@link
 * #GUARD} is keyed by its Guard Kit Stand's own entity UUID instead (a single plot can have multiple
 * stands); {@link #WANDERING} is keyed by the conversing entity's own UUID -- covers a real vanilla
 * Wandering Trader, or any other entity Audyble's conversation engine decides is conversational that
 * Settlemynts has no other record of at all (e.g. a manually `/audyble npc bind`-bound test entity).
 * No longer needs to avoid depending on this mod's own {@code zone.PlotWorkerKind.Kind} the way
 * Audyble's old copy did -- that isolation only existed because Audyble owned this type across a
 * mod boundary; now that it's native to Settlemynts, {@link com.github.cerealklla.settlemynts.zone.PlotWorkerKind.Kind}
 * stays its own, narrower enum (no GUARD/WANDERING concept) for its own, narrower purpose (which of
 * the 4 ticker types a plot's worker currently is), and this is a deliberately separate, wider enum.
 */
public enum NpcRole {
    FARMER, LUMBERJACK, MINER, RESIDENT, GUARD, WANDERING;

    public static final Codec<NpcRole> CODEC = Codec.STRING.xmap(
            s -> NpcRole.valueOf(s.toUpperCase(java.util.Locale.ROOT)), Enum::name);
}
