package com.github.cerealklla.settlemynts.founding;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client-to-server: requests a Planned Perimeter Stake item from {@code client.FoundingScreen} (design doc Section 6). Server re-checks the requester is a current Town Planner. */
public record RequestPerimeterStakePayload(int coreEntityId) implements CustomPacketPayload {

    public static final Type<RequestPerimeterStakePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "request_perimeter_stake"));

    public static final StreamCodec<ByteBuf, RequestPerimeterStakePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, RequestPerimeterStakePayload::coreEntityId,
            RequestPerimeterStakePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
