package com.github.cerealklla.settlemynts.zone;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: "Finalize Plot" on a {@code PlotStakeScreen}. {@code stakeEntityId} identifies
 * which in-progress plot (via that stake's {@code ownerCoreId}/{@code plotSessionId}) -- the
 * server re-resolves both from the live entity rather than trusting a client-supplied id for
 * either, same "never trust the client for identity" precedent as every other payload here.
 */
public record FinalizePlotPayload(int stakeEntityId, String name, String zoneTypeId) implements CustomPacketPayload {

    public static final Type<FinalizePlotPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "finalize_plot"));

    public static final StreamCodec<ByteBuf, FinalizePlotPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, FinalizePlotPayload::stakeEntityId,
            ByteBufCodecs.STRING_UTF8, FinalizePlotPayload::name,
            ByteBufCodecs.STRING_UTF8, FinalizePlotPayload::zoneTypeId,
            FinalizePlotPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
