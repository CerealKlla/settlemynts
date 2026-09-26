package com.github.cerealklla.settlemynts.founding;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client-to-server: removes a stake from {@code client.StakeScreen} (design doc Section 6: "remove the stake if they need to move it"). The item is handed back to the requesting player. */
public record RemoveStakePayload(int stakeEntityId) implements CustomPacketPayload {

    public static final Type<RemoveStakePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "remove_stake"));

    public static final StreamCodec<ByteBuf, RemoveStakePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, RemoveStakePayload::stakeEntityId,
            RemoveStakePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
