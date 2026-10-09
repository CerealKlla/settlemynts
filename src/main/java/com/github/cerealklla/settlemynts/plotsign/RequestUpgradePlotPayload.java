package com.github.cerealklla.settlemynts.plotsign;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: "Upgrade Plot" button on {@code client.PlotConfigSignMenuScreen} (added
 * 2026-10-09, explicit request: "All Plot Manager menus for plots need an 'Upgrade Plot' button,
 * same options as upgrading structures"). Server re-checks {@code canManage} itself (same
 * precedent as "Configure Garrison"/"Plot Management") before handing off to {@code
 * bridge.BlueprintsConstructionBridge#requestUpgrade}, which opens the Blueprint picker for this
 * plot's next Tier up.
 */
public record RequestUpgradePlotPayload(BlockPos signPos) implements CustomPacketPayload {

    public static final Type<RequestUpgradePlotPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "request_upgrade_plot"));

    public static final StreamCodec<ByteBuf, RequestUpgradePlotPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), RequestUpgradePlotPayload::signPos,
            RequestUpgradePlotPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
