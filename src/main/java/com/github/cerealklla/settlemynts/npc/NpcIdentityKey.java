package com.github.cerealklla.settlemynts.npc;

import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;

/**
 * The stable identity key for one NPC "character" -- moved here from Audyble's own {@code
 * npc.NpcIdentityKey}, 2026-11-05 (see {@code NpcIdentityStore}'s own class doc for the full
 * ownership-move writeup). For a plot-bound role (Farmer/Lumberjack/Miner/Resident), {@code ownerId}
 * is that worker's owning plot's own {@code zone.PlotRecord#plotId} -- already a stable, globally-
 * unique UUID every spawn ticker has in hand, so no dedicated field anywhere else is needed to carry
 * this. For {@link NpcRole#GUARD}, {@code ownerId} is the Guard Kit Stand's own UUID instead -- a
 * single plot can have multiple stands (multiple guards), and a stand's UUID is already this suite's
 * own de facto "slot identity" ({@code guardhouse.GuardKitStandIndex}'s map key). For {@link
 * NpcRole#WANDERING}, {@code ownerId} is the conversing entity's own UUID -- identity survives only
 * as long as that specific entity does.
 */
public record NpcIdentityKey(UUID ownerId, NpcRole role) {

    public static final Codec<NpcIdentityKey> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("owner_id").forGetter(NpcIdentityKey::ownerId),
            NpcRole.CODEC.fieldOf("role").forGetter(NpcIdentityKey::role)
    ).apply(i, NpcIdentityKey::new));

    public static NpcIdentityKey wandering(UUID entityId) {
        return new NpcIdentityKey(entityId, NpcRole.WANDERING);
    }
}
