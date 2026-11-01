package com.github.cerealklla.settlemynts.tavern;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client-to-server: the player clicked the action button on {@code client.TavernHireScreen} -- see {@link OpenTavernHireMenuPayload}'s own doc. */
public record TavernHireAcceptPayload(int npcEntityId) implements CustomPacketPayload {

    public static final Type<TavernHireAcceptPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "tavern_hire_accept"));

    public static final StreamCodec<ByteBuf, TavernHireAcceptPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, TavernHireAcceptPayload::npcEntityId,
            TavernHireAcceptPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
