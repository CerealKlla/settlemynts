package com.github.cerealklla.settlemynts.resident;

import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;

/**
 * One real bed in a natural village, bound to at most one living plain {@code Villager} at a time --
 * explicit user redesign, 2026-11-17, replacing the old "respawn at the exact death position, if
 * within some margin of the settlement's registered polygon" mechanism ({@code
 * VillagerRespawnQueue}/{@code VillagerRespawnTicker}/{@code PendingVillagerRespawn(Index)}, all
 * deleted) entirely: "use the beds in the NPC settlement to bind NPCs to spawn points, not the
 * settlement itself... each bed in the settlement gets an NPC" -- the same slot-ownership model
 * {@code guardhouse.GuardKitStandEntity} already uses for guards. Real motivating gap found the same
 * day investigating a "no NPCs are respawning" report: the old system queued a respawn at wherever a
 * villager actually died (often deep in a cave, off a cliff, or wherever a chase ended), which is
 * frequently a terrible, zombie-infested, or out-of-settlement-margin spot -- binding to a bed
 * instead guarantees every respawn lands somewhere sensible (inside an actual house) every time.
 *
 * <p>A bed has no block entity of its own to carry this state directly (unlike a Guard Kit Stand, a
 * real placed entity) -- tracked externally here instead, keyed by the bed's own world position (see
 * {@link BedSpawnSlotIndex}).
 */
public record BedSpawnSlot(Identifier dimension, BlockPos bedPos, long settlementEntityId,
                            Optional<UUID> villagerId, long respawnReadyAtGameTime) {

    public static final Codec<BedSpawnSlot> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("dimension").forGetter(BedSpawnSlot::dimension),
            BlockPos.CODEC.fieldOf("bed_pos").forGetter(BedSpawnSlot::bedPos),
            Codec.LONG.fieldOf("settlement_entity_id").forGetter(BedSpawnSlot::settlementEntityId),
            UUIDUtil.CODEC.optionalFieldOf("villager_id").forGetter(BedSpawnSlot::villagerId),
            Codec.LONG.fieldOf("respawn_ready_at_game_time").forGetter(BedSpawnSlot::respawnReadyAtGameTime)
    ).apply(i, BedSpawnSlot::new));

    public BedSpawnSlot withVillager(UUID id) {
        return new BedSpawnSlot(dimension, bedPos, settlementEntityId, Optional.of(id), respawnReadyAtGameTime);
    }

    /** Called the instant the bound villager is confirmed gone (death, or converted to a zombie) -- clears the slot and starts its respawn timer. */
    public BedSpawnSlot vacated(long newRespawnReadyAtGameTime) {
        return new BedSpawnSlot(dimension, bedPos, settlementEntityId, Optional.empty(), newRespawnReadyAtGameTime);
    }
}
