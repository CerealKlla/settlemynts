package com.github.cerealklla.settlemynts.zone;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client-to-server: "Get Plot Placement Stake" on a finalized settlement's Town Hall Core screen. */
public record RequestPlotStakePayload(int coreEntityId) implements CustomPacketPayload {

    public static final Type<RequestPlotStakePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "request_plot_stake"));

    public static final StreamCodec<ByteBuf, RequestPlotStakePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, RequestPlotStakePayload::coreEntityId,
            RequestPlotStakePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
