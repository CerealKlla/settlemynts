package com.github.cerealklla.settlemynts.founding;

import java.util.UUID;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: one row's "Set" button on {@code client.DefenseSpendingScreen}. {@code
 * coreEntityId} is re-resolved server-side to the Town Hall Core itself for a fresh {@code
 * isTownPlanner} check (never trust the screen was still valid) -- {@code guardhousePlotId} is then
 * matched against that SAME settlement's own plot list before being applied, so a stale/forged id
 * from a different settlement can never touch another settlement's Guardhouse.
 */
public record SetDefenseSpendingPayload(int coreEntityId, UUID guardhousePlotId, int allocation) implements CustomPacketPayload {

    public static final Type<SetDefenseSpendingPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "set_defense_spending"));

    public static final StreamCodec<ByteBuf, SetDefenseSpendingPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetDefenseSpendingPayload::coreEntityId,
            UUIDUtil.STREAM_CODEC, SetDefenseSpendingPayload::guardhousePlotId,
            ByteBufCodecs.VAR_INT, SetDefenseSpendingPayload::allocation,
            SetDefenseSpendingPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
