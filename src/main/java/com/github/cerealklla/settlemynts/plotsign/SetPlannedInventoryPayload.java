package com.github.cerealklla.settlemynts.plotsign;

import java.util.List;

import io.netty.buffer.ByteBuf;

import com.mojang.serialization.Codec;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: "Save Changes" on {@code client.PlannedInventoryScreen} (2026-10-09) -- mirrors
 * {@link SetShopListingsPayload} exactly. One batch covering every row the owner saw.
 */
public record SetPlannedInventoryPayload(BlockPos signPos, List<PlannedInventoryUpdate> updates) implements CustomPacketPayload {

    public static final Type<SetPlannedInventoryPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "set_planned_inventory"));

    private static final Codec<List<PlannedInventoryUpdate>> UPDATES_CODEC = PlannedInventoryUpdate.CODEC.listOf();

    public static final StreamCodec<ByteBuf, SetPlannedInventoryPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), SetPlannedInventoryPayload::signPos,
            ByteBufCodecs.fromCodec(UPDATES_CODEC), SetPlannedInventoryPayload::updates,
            SetPlannedInventoryPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
