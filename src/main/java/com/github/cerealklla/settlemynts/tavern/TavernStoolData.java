package com.github.cerealklla.settlemynts.tavern;

import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * A granted-but-not-yet-placed Tavern Stool item's own metadata -- which settlement/plot it was
 * issued for, so {@link TavernStoolItem#useOn} can re-validate it's being placed inside that exact
 * plot. Unlike {@code guardhouse.GuardKitStandData}, a Tavern Stool is a real {@code
 * com.github.cerealklla.lyfe.rest.StoolBlock} once placed (see {@link TavernStoolIndex}), not a
 * picked-up entity -- no stool identity or seated-NPC state needs to survive in the item itself,
 * since pickup is only ever offered on an already-empty stool (see
 * {@link TavernStoolPickupListener}).
 */
public record TavernStoolData(UUID settlementCoreId, UUID plotId) {

    public static final Codec<TavernStoolData> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("settlement_core_id").forGetter(TavernStoolData::settlementCoreId),
            UUIDUtil.CODEC.fieldOf("plot_id").forGetter(TavernStoolData::plotId)
    ).apply(i, TavernStoolData::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, TavernStoolData> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, TavernStoolData::settlementCoreId,
            UUIDUtil.STREAM_CODEC, TavernStoolData::plotId,
            TavernStoolData::new);
}
