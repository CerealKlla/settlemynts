package com.github.cerealklla.settlemynts.zone;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server-to-client: opens {@code client.PlotStakeScreen} for a {@link GhostPlotStakeEntity} a Town
 * Planner just interacted with (design doc Section 11a). No zone-type list is sent -- {@code
 * ZoneTypeRegistry} is populated identically on both sides at mod construction, so the client
 * reads it directly rather than the server pushing a redundant copy over the wire.
 */
public record OpenPlotStakeScreenPayload(int stakeEntityId, int stakeCount, boolean valid, boolean hasRoadAccessFlag) implements CustomPacketPayload {

    public static final Type<OpenPlotStakeScreenPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "open_plot_stake_screen"));

    public static final StreamCodec<ByteBuf, OpenPlotStakeScreenPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, OpenPlotStakeScreenPayload::stakeEntityId,
            ByteBufCodecs.VAR_INT, OpenPlotStakeScreenPayload::stakeCount,
            ByteBufCodecs.BOOL, OpenPlotStakeScreenPayload::valid,
            ByteBufCodecs.BOOL, OpenPlotStakeScreenPayload::hasRoadAccessFlag,
            OpenPlotStakeScreenPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
