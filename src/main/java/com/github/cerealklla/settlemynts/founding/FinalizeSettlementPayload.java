package com.github.cerealklla.settlemynts.founding;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: requests running the perimeter auto-fit ({@link PerimeterFit}, design doc
 * Section 7) from {@code client.FoundingScreen}. As of 2026-09-26 this only runs the fit and
 * repositions the stakes -- it does **not** yet solidify anything, register the settlement with
 * Cartographyr, or enable protection (design doc Section 8), which is a separate, later milestone.
 * Any current Town Planner may trigger this (design doc Section 6: "as long as the shape... they
 * will have a button to finalize the settlement").
 */
public record FinalizeSettlementPayload(int coreEntityId) implements CustomPacketPayload {

    public static final Type<FinalizeSettlementPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "finalize_settlement"));

    public static final StreamCodec<ByteBuf, FinalizeSettlementPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, FinalizeSettlementPayload::coreEntityId,
            FinalizeSettlementPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
