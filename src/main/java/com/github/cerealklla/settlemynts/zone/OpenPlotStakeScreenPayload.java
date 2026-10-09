package com.github.cerealklla.settlemynts.zone;

import java.util.ArrayList;
import java.util.List;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server-to-client: opens {@code client.PlotStakeScreen} for a {@link GhostPlotStakeEntity} a Town
 * Planner just interacted with (design doc Section 11a). The full zone-type list itself isn't sent
 * -- {@code ZoneTypeRegistry} is populated identically on both sides at mod construction, so the
 * client reads it directly rather than the server pushing a redundant copy over the wire.
 * {@code allowedZoneTypeIds} (added 2026-10-09, explicit request: "I'd like to not even show them as
 * options in the plot type picker if they're unavailable") IS sent, since eligibility (Town Hall
 * Tier + Mayor level, see {@code zone.ZoneTypeUnlocks#isAllowed}) is real server state the client has
 * no other way to know -- {@code client.PlotStakeScreen} filters its own locally-read registry list
 * down to just these ids before ever showing a cycle option.
 */
public record OpenPlotStakeScreenPayload(int stakeEntityId, int stakeCount, boolean valid, boolean hasRoadAccessFlag,
                                          List<String> allowedZoneTypeIds) implements CustomPacketPayload {

    public static final Type<OpenPlotStakeScreenPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "open_plot_stake_screen"));

    public static final StreamCodec<ByteBuf, OpenPlotStakeScreenPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, OpenPlotStakeScreenPayload::stakeEntityId,
            ByteBufCodecs.VAR_INT, OpenPlotStakeScreenPayload::stakeCount,
            ByteBufCodecs.BOOL, OpenPlotStakeScreenPayload::valid,
            ByteBufCodecs.BOOL, OpenPlotStakeScreenPayload::hasRoadAccessFlag,
            ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8), OpenPlotStakeScreenPayload::allowedZoneTypeIds,
            OpenPlotStakeScreenPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
