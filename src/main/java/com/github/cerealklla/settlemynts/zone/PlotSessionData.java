package com.github.cerealklla.settlemynts.zone;

import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * A "Plot Stakes" item's own metadata (Plot Stakes rework, 2026-09-30): {@code ownerCoreId} is the
 * settlement that granted this item, set once at grant time and permanent for the item's lifetime
 * (needed for the Town-Planner check and the "walked out of the settlement" auto-removal). {@code
 * plotSessionId} is the item's own "CurrentPlotID" -- which in-progress plot a placed Plot Fence
 * Post will be grouped into next -- and is deliberately mutable and nullable ({@code null} = blank):
 * it starts blank on a freshly-granted item, is filled in (randomly, if still blank) the first time
 * the item places a fresh post, is overwritten with an existing post's own {@code PlotID} whenever
 * that post is right-clicked to resume from, and is reset back to {@code null} the moment the player
 * switches away from this item in their hand -- see {@code PlotPlacementStakeItem}, {@code
 * GhostPlotStakeEntity#interact}, and {@code SettlemyntsMod#onLeashTick}.
 */
public record PlotSessionData(UUID ownerCoreId, UUID plotSessionId) {

    public static final Codec<PlotSessionData> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("owner_core_id").forGetter(PlotSessionData::ownerCoreId),
            UUIDUtil.CODEC.optionalFieldOf("plot_session_id").forGetter(d -> Optional.ofNullable(d.plotSessionId()))
    ).apply(i, (ownerCoreId, plotSessionId) -> new PlotSessionData(ownerCoreId, plotSessionId.orElse(null))));

    public static final StreamCodec<RegistryFriendlyByteBuf, PlotSessionData> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, PlotSessionData::ownerCoreId,
            ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC), d -> Optional.ofNullable(d.plotSessionId()),
            (ownerCoreId, plotSessionId) -> new PlotSessionData(ownerCoreId, plotSessionId.orElse(null)));
}
