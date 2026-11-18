package com.github.cerealklla.settlemynts.plotsign;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: "Set Owner"/"Clear (NPC-owned)" on {@code client.ChangeOwnerScreen}. {@code
 * ownerName} blank means NPC-owned (same convention Finalize's own Owner field already uses); a
 * non-blank name is resolved via {@code MinecraftServer#services()#nameToIdCache()#get(name)} -- the
 * real vanilla name->UUID resolver, not an online-players-only lookup -- so a Town Planner can assign
 * an offline player as Owner, unlike Finalize's own Owner field (explicit request: "change the owner
 * of a plot at any time"). Re-validates {@code founding.GhostTownHallCoreEntity#isTownPlanner}
 * itself; the real mutation (and Protectyons re-sync) goes through the same {@code
 * zone.PlotPermissions#applyOwnerChange} the admin {@code /settlemynts setplotowner} debug command
 * also uses, so the two can never drift apart.
 */
public record SetPlotOwnerFromSignPayload(BlockPos signPos, String ownerName) implements CustomPacketPayload {

    public static final Type<SetPlotOwnerFromSignPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "set_plot_owner_from_sign"));

    public static final StreamCodec<ByteBuf, SetPlotOwnerFromSignPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), SetPlotOwnerFromSignPayload::signPos,
            ByteBufCodecs.STRING_UTF8, SetPlotOwnerFromSignPayload::ownerName,
            SetPlotOwnerFromSignPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
