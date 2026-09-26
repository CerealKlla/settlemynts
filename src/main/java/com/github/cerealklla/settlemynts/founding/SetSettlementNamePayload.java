package com.github.cerealklla.settlemynts.founding;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client-to-server: sets a settlement's name from {@code client.FoundingScreen} (design doc Section 6). Any current Town Planner may do this, not just the founder. */
public record SetSettlementNamePayload(int coreEntityId, String name) implements CustomPacketPayload {

    public static final Type<SetSettlementNamePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "set_settlement_name"));

    public static final StreamCodec<ByteBuf, SetSettlementNamePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetSettlementNamePayload::coreEntityId,
            ByteBufCodecs.STRING_UTF8, SetSettlementNamePayload::name,
            SetSettlementNamePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
