package com.github.cerealklla.settlemynts.plotsign;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: "Hire NPC" button on {@code client.PlotConfigSignMenuScreen} (design doc
 * Section 14a, explicit user request, 2026-10-15). Server re-checks {@code canManage} and that the
 * plot is player-owned itself, same "visible but re-validated" discipline as every other button
 * here -- see {@code SettlemyntsMod#requestHireNpc} and {@code zone.NpcHireMaintenance}.
 */
public record RequestHireNpcPayload(BlockPos signPos) implements CustomPacketPayload {

    public static final Type<RequestHireNpcPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "request_hire_npc"));

    public static final StreamCodec<ByteBuf, RequestHireNpcPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), RequestHireNpcPayload::signPos,
            RequestHireNpcPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
