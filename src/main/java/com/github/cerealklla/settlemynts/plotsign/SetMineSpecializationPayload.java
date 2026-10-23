package com.github.cerealklla.settlemynts.plotsign;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;
import com.github.cerealklla.settlemynts.mineshaft.MineSpecialization;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: the "Configure" tab's Ore/Stone buttons (2026-10-23, explicit user request).
 * Server re-validates {@code zone.PlotPermissions#canManage} and that the plot is actually a
 * Mineshaft (the same "never trust the client for a privileged action" precedent as every other
 * payload here) -- see {@code SettlemyntsMod#setMineSpecialization}.
 */
public record SetMineSpecializationPayload(BlockPos signPos, MineSpecialization specialization) implements CustomPacketPayload {

    public static final Type<SetMineSpecializationPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "set_mine_specialization"));

    public static final StreamCodec<ByteBuf, SetMineSpecializationPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), SetMineSpecializationPayload::signPos,
            ByteBufCodecs.STRING_UTF8.map(MineSpecialization::valueOf, MineSpecialization::name), SetMineSpecializationPayload::specialization,
            SetMineSpecializationPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
