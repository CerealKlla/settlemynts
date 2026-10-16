package com.github.cerealklla.settlemynts.founding;

import java.util.ArrayList;
import java.util.List;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server-to-client: opens {@code client.DefenseSpendingScreen} with every Guardhouse in the settlement and its current allocation. */
public record OpenDefenseSpendingMenuPayload(int coreEntityId, List<DefenseSpendingEntry> guardhouses) implements CustomPacketPayload {

    public static final Type<OpenDefenseSpendingMenuPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "open_defense_spending"));

    public static final StreamCodec<ByteBuf, OpenDefenseSpendingMenuPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, OpenDefenseSpendingMenuPayload::coreEntityId,
            ByteBufCodecs.collection(ArrayList::new, DefenseSpendingEntry.STREAM_CODEC), OpenDefenseSpendingMenuPayload::guardhouses,
            OpenDefenseSpendingMenuPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
