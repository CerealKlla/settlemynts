package com.github.cerealklla.settlemynts.plotsign;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: {@code client.RadioTab} on open (mirrors {@link RequestPlannedInventoryPayload}'s
 * own shape). Server re-checks {@code zone.PlotPermissions#canManage} and replies with
 * {@link OpenMurmurRadioPayload}.
 */
public record RequestMurmurRadioPayload(BlockPos signPos) implements CustomPacketPayload {

    public static final Type<RequestMurmurRadioPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "request_murmur_radio"));

    public static final StreamCodec<ByteBuf, RequestMurmurRadioPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), RequestMurmurRadioPayload::signPos,
            RequestMurmurRadioPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
