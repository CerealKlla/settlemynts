package com.github.cerealklla.settlemynts.founding;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client-to-server: sets a settlement's flat sales tax from {@code client.FoundingScreen} (explicit user spec, 2026-10-17). Any current Town Planner may do this, same permission as {@link SetSettlementNamePayload}. */
public record SetTaxRatePayload(int coreEntityId, int taxPerTransaction) implements CustomPacketPayload {

    public static final Type<SetTaxRatePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "set_tax_rate"));

    public static final StreamCodec<ByteBuf, SetTaxRatePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetTaxRatePayload::coreEntityId,
            ByteBufCodecs.VAR_INT, SetTaxRatePayload::taxPerTransaction,
            SetTaxRatePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
