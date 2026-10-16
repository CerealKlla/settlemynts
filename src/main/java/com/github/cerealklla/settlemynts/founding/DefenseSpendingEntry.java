package com.github.cerealklla.settlemynts.founding;

import java.util.UUID;

import io.netty.buffer.ByteBuf;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * One Guardhouse's current "Defense Spending" allocation, as shown on {@code
 * client.DefenseSpendingScreen} (explicit user spec, 2026-10-16: "Town Hall Core should have a menu
 * option for 'Defense Spending'... specify how much money to top up into each Guardhouse in the
 * settlement independently"). {@code plotId} is the Guardhouse plot's own identity, re-validated
 * server-side on every {@code SetDefenseSpendingPayload} -- never trusted from the client beyond
 * picking which row was edited.
 */
public record DefenseSpendingEntry(UUID plotId, String plotName, int currentAllocation) {

    public static final StreamCodec<ByteBuf, DefenseSpendingEntry> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, DefenseSpendingEntry::plotId,
            ByteBufCodecs.STRING_UTF8, DefenseSpendingEntry::plotName,
            ByteBufCodecs.VAR_INT, DefenseSpendingEntry::currentAllocation,
            DefenseSpendingEntry::new);
}
