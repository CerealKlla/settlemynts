package com.github.cerealklla.settlemynts.plotsign;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: "Defense Spending" button on {@code client.PlotConfigSignMenuScreen} -- only
 * ever shown on the Town Hall plot's own sign, same "city-hall-wide admin view" precedent as "Plot
 * Management"/"Establish Highway". Server re-checks {@code canManage} itself.
 */
public record RequestDefenseSpendingPayload(BlockPos signPos) implements CustomPacketPayload {

    public static final Type<RequestDefenseSpendingPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "request_defense_spending"));

    public static final StreamCodec<ByteBuf, RequestDefenseSpendingPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), RequestDefenseSpendingPayload::signPos,
            RequestDefenseSpendingPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
