package com.github.cerealklla.settlemynts.tavern;

import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * A picked-up Tavern Stool item's own metadata -- direct mirror of {@code
 * guardhouse.GuardKitStandData}, minus the Kyt-binding/respawn-timer fields Guard Kit Stands carry
 * (a Tavern Stool holds no bound loadout of its own -- any seated NPC is a separate, independently
 * tracked entity). {@code seatedNpcId} survives a pickup/replace cycle so re-placing a stool that
 * still had an NPC on it (picked up mid-interaction, an edge case rather than the normal flow)
 * doesn't orphan that NPC.
 */
public record TavernStoolData(UUID stoolId, UUID settlementCoreId, UUID plotId, java.util.Optional<UUID> seatedNpcId) {

    public static final Codec<TavernStoolData> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("stool_id").forGetter(TavernStoolData::stoolId),
            UUIDUtil.CODEC.fieldOf("settlement_core_id").forGetter(TavernStoolData::settlementCoreId),
            UUIDUtil.CODEC.fieldOf("plot_id").forGetter(TavernStoolData::plotId),
            UUIDUtil.CODEC.optionalFieldOf("seated_npc_id").forGetter(TavernStoolData::seatedNpcId)
    ).apply(i, TavernStoolData::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, TavernStoolData> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, TavernStoolData::stoolId,
            UUIDUtil.STREAM_CODEC, TavernStoolData::settlementCoreId,
            UUIDUtil.STREAM_CODEC, TavernStoolData::plotId,
            ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC), TavernStoolData::seatedNpcId,
            TavernStoolData::new);
}
