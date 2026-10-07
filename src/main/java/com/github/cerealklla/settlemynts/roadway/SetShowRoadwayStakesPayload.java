package com.github.cerealklla.settlemynts.roadway;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client-to-server: "Show Roadway Stakes" toggle on a finalized settlement's Town Hall Core screen. */
public record SetShowRoadwayStakesPayload(int coreEntityId, boolean visible) implements CustomPacketPayload {

    public static final Type<SetShowRoadwayStakesPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "set_show_roadway_stakes"));

    public static final StreamCodec<ByteBuf, SetShowRoadwayStakesPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetShowRoadwayStakesPayload::coreEntityId,
            ByteBufCodecs.BOOL, SetShowRoadwayStakesPayload::visible,
            SetShowRoadwayStakesPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
