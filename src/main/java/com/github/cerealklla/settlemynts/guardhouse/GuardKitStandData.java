package com.github.cerealklla.settlemynts.guardhouse;

import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * A picked-up Guard Kit Stand item's own metadata -- carries exactly the fields {@link
 * GuardKitStandEntity} needs to resume being "the same" stand when re-placed (see {@link
 * GuardKitStandItem#useOn}, which calls {@code Entity#setUUID(standId)} on the freshly-constructed
 * entity, safe since the original was fully discarded on pickup). {@code settlementCoreId}/{@code
 * plotId} are also what the leaving-the-plot auto-removal check ({@code
 * SettlemyntsMod#removeStaleGuardKitStandsOutsidePlot}) resolves the owning plot from, directly off
 * the held item -- no reverse lookup needed.
 */
public record GuardKitStandData(UUID standId, UUID settlementCoreId, UUID plotId, Optional<String> boundKytName,
                                 Optional<UUID> assignedGuardId, long respawnReadyAtGameTime) {

    public static final Codec<GuardKitStandData> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("stand_id").forGetter(GuardKitStandData::standId),
            UUIDUtil.CODEC.fieldOf("settlement_core_id").forGetter(GuardKitStandData::settlementCoreId),
            UUIDUtil.CODEC.fieldOf("plot_id").forGetter(GuardKitStandData::plotId),
            Codec.STRING.optionalFieldOf("bound_kyt_name").forGetter(GuardKitStandData::boundKytName),
            UUIDUtil.CODEC.optionalFieldOf("assigned_guard_id").forGetter(GuardKitStandData::assignedGuardId),
            Codec.LONG.fieldOf("respawn_ready_at_game_time").forGetter(GuardKitStandData::respawnReadyAtGameTime)
    ).apply(i, GuardKitStandData::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, GuardKitStandData> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, GuardKitStandData::standId,
            UUIDUtil.STREAM_CODEC, GuardKitStandData::settlementCoreId,
            UUIDUtil.STREAM_CODEC, GuardKitStandData::plotId,
            ByteBufCodecs.optional(ByteBufCodecs.STRING_UTF8), GuardKitStandData::boundKytName,
            ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC), GuardKitStandData::assignedGuardId,
            ByteBufCodecs.VAR_LONG, GuardKitStandData::respawnReadyAtGameTime,
            GuardKitStandData::new);
}
