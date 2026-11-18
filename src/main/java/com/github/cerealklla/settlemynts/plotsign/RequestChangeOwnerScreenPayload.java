package com.github.cerealklla.settlemynts.plotsign;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: "Change Owner" button on {@code client.ConfigureTab}. Added 2026-11-17, explicit
 * request: "allow the mayor/town planners to change the owner of a plot at any time" -- deliberately
 * separate from {@code zone.PlotPermissions#canManage}, which (since 2026-10-05) locks a non-founder
 * Town Planner out of a plot someone else already privately owns; this control is gated on {@code
 * founding.GhostTownHallCoreEntity#isTownPlanner} alone (which already includes the Mayor, auto-added
 * at founding), same as every other Town-Planner-wide control in this mod. The server re-resolves
 * the plot/permission itself -- {@code client.OpenPlotConfigSignMenuPayload#canChangeOwner} is only
 * ever a display hint.
 */
public record RequestChangeOwnerScreenPayload(BlockPos signPos) implements CustomPacketPayload {

    public static final Type<RequestChangeOwnerScreenPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "request_change_owner_screen"));

    public static final StreamCodec<ByteBuf, RequestChangeOwnerScreenPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), RequestChangeOwnerScreenPayload::signPos,
            RequestChangeOwnerScreenPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
