package com.github.cerealklla.settlemynts.resident;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

/**
 * A dead natural-village {@code Villager}'s recorded comeback, keyed against an absolute game tick
 * -- same "store a target tick, compare it from a periodic ticker" shape as {@code
 * farm.FarmerWorkGoal$AvoidedTarget}/{@code zone.ShopMidnightRestockTicker}, rather than a new
 * scheduling idiom. See {@link VillagerDeathListener} (creates these) and {@link
 * VillagerRespawnTicker} (consumes them).
 *
 * <p>Deliberately doesn't record the dead villager's profession -- {@link VillagerRespawnTicker}
 * spawns a fresh, unemployed villager at {@code deathPos} and relies on vanilla's own existing
 * job-site-claiming AI to pick the (now vacant) profession back up, rather than this mod reaching
 * into {@code VillagerData}'s mutation API itself.
 */
public record PendingVillagerRespawn(long settlementEntityId, BlockPos deathPos, Identifier dimension,
                                      long respawnAtTick) {

    public static final Codec<PendingVillagerRespawn> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.LONG.fieldOf("settlement_entity_id").forGetter(PendingVillagerRespawn::settlementEntityId),
            BlockPos.CODEC.fieldOf("death_pos").forGetter(PendingVillagerRespawn::deathPos),
            Identifier.CODEC.fieldOf("dimension").forGetter(PendingVillagerRespawn::dimension),
            Codec.LONG.fieldOf("respawn_at_tick").forGetter(PendingVillagerRespawn::respawnAtTick)
    ).apply(i, PendingVillagerRespawn::new));
}
