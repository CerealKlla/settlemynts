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
 * Server-to-client: opens {@code client.PlannedInventoryScreen} (2026-10-09). {@code entries} is the
 * union of three sources (live box stock, any existing {@code zone.PlannedInventoryTarget}, and the
 * Zone Type's catalog candidates) -- see {@code SettlemyntsMod#requestPlannedInventory}'s own doc for
 * the exact assembly, mirroring {@code OpenShopPayload}'s manage-mode row union.
 */
public record OpenPlannedInventoryPayload(BlockPos signPos, List<PlannedInventoryEntry> entries) implements CustomPacketPayload {

    public static final Type<OpenPlannedInventoryPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "open_planned_inventory"));

    private static final Codec<List<PlannedInventoryEntry>> ENTRIES_CODEC = PlannedInventoryEntry.CODEC.listOf();

    public static final StreamCodec<ByteBuf, OpenPlannedInventoryPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), OpenPlannedInventoryPayload::signPos,
            ByteBufCodecs.fromCodec(ENTRIES_CODEC), OpenPlannedInventoryPayload::entries,
            OpenPlannedInventoryPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
