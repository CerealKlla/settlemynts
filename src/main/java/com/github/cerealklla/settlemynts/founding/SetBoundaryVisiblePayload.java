package com.github.cerealklla.settlemynts.founding;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client-to-server: toggles "View Settlement Boundaries" (design doc Section 9) from {@code client.FoundingScreen}. Any current Town Planner may toggle it. */
public record SetBoundaryVisiblePayload(int coreEntityId, boolean visible) implements CustomPacketPayload {

    public static final Type<SetBoundaryVisiblePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "set_boundary_visible"));

    public static final StreamCodec<ByteBuf, SetBoundaryVisiblePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetBoundaryVisiblePayload::coreEntityId,
            ByteBufCodecs.BOOL, SetBoundaryVisiblePayload::visible,
            SetBoundaryVisiblePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
