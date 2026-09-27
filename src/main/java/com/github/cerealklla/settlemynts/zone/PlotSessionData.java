package com.github.cerealklla.settlemynts.zone;

import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * Which settlement and which in-progress plot a held {@link PlotPlacementStakeItem} stack belongs
 * to -- embedded directly in the item stack as a data component at grant time (see {@code
 * SettlemyntsMod}'s "Get Plot Placement Stake" handler), so placing it never has to guess which
 * plot the stake is for the way {@code founding.PlannedPerimeterStakeItem} has to resolve "nearest
 * settlement." {@code plotSessionId} groups every stake placed with copies of the same granted
 * stack into one plot -- see {@code GhostPlotStakeEntity}.
 */
public record PlotSessionData(UUID ownerCoreId, UUID plotSessionId) {

    public static final Codec<PlotSessionData> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("owner_core_id").forGetter(PlotSessionData::ownerCoreId),
            UUIDUtil.CODEC.fieldOf("plot_session_id").forGetter(PlotSessionData::plotSessionId)
    ).apply(i, PlotSessionData::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, PlotSessionData> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, PlotSessionData::ownerCoreId,
            UUIDUtil.STREAM_CODEC, PlotSessionData::plotSessionId,
            PlotSessionData::new);
}
