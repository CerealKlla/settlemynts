package com.github.cerealklla.settlemynts.plotsign;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: "Relocate Plot Sign" on {@code client.PlotConfigureScreen}. Mirrors Blueprynts'
 * "Reposition Supply Box" -- removes the sign at {@code signPos}, stashes its identity in {@link
 * PendingPlotConfigSignRelocation}, and grants a {@code PlotConfigSignRelocatorItem}. The server
 * re-checks {@code zone.PlotPermissions#canManage} itself rather than trusting the client's earlier
 * {@code canManage} flag, same "never trust the client for a privileged action" precedent as every
 * other payload here.
 */
public record RelocatePlotSignPayload(BlockPos signPos) implements CustomPacketPayload {

    public static final Type<RelocatePlotSignPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "relocate_plot_sign"));

    public static final StreamCodec<ByteBuf, RelocatePlotSignPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), RelocatePlotSignPayload::signPos,
            RelocatePlotSignPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
