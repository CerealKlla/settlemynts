package com.github.cerealklla.settlemynts.zone;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client-to-server: "Remove Stake" on a {@code PlotStakeScreen}. */
public record RemovePlotStakePayload(int stakeEntityId) implements CustomPacketPayload {

    public static final Type<RemovePlotStakePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "remove_plot_stake"));

    public static final StreamCodec<ByteBuf, RemovePlotStakePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, RemovePlotStakePayload::stakeEntityId,
            RemovePlotStakePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
