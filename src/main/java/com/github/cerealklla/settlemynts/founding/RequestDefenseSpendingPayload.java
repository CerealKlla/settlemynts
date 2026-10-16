package com.github.cerealklla.settlemynts.founding;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: "Defense Spending..." button on {@code client.FoundingScreen} -- the Ghost Town
 * Hall Core's own direct interaction menu, explicitly where the user asked for this (not the Plot
 * Config Sign). Same {@code coreEntityId}-keyed shape as {@link SetBoundaryVisiblePayload}; server
 * re-checks {@code GhostTownHallCoreEntity#isTownPlanner} itself.
 */
public record RequestDefenseSpendingPayload(int coreEntityId) implements CustomPacketPayload {

    public static final Type<RequestDefenseSpendingPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "request_defense_spending"));

    public static final StreamCodec<ByteBuf, RequestDefenseSpendingPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, RequestDefenseSpendingPayload::coreEntityId,
            RequestDefenseSpendingPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
