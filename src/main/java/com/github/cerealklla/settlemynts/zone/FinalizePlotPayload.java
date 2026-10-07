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
 *
 * <p>{@code ownerName} (added 2026-09-30) -- design doc Section 10's "Owner -- a player name, or an
 * NPC (defaults to NPC)." An empty string means NPC-owned; a non-empty name is resolved server-side
 * via the vanilla profile cache in {@code SettlemyntsMod#finalizePlot} (never trusted as a UUID from
 * the client).
 */
public record FinalizePlotPayload(int stakeEntityId, String name, String zoneTypeId, String ownerName) implements CustomPacketPayload {

    public static final Type<FinalizePlotPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "finalize_plot"));

    public static final StreamCodec<ByteBuf, FinalizePlotPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, FinalizePlotPayload::stakeEntityId,
            ByteBufCodecs.STRING_UTF8, FinalizePlotPayload::name,
            ByteBufCodecs.STRING_UTF8, FinalizePlotPayload::zoneTypeId,
            ByteBufCodecs.STRING_UTF8, FinalizePlotPayload::ownerName,
            FinalizePlotPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
