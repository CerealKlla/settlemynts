package com.github.cerealklla.settlemynts.founding;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client-to-server: "Reposition Town Hall Core" button on a finalized settlement's Town Hall Core screen. */
public record RepositionTownHallCorePayload(int coreEntityId) implements CustomPacketPayload {

    public static final Type<RepositionTownHallCorePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "reposition_town_hall_core"));

    public static final StreamCodec<ByteBuf, RepositionTownHallCorePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, RepositionTownHallCorePayload::coreEntityId,
            RepositionTownHallCorePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
