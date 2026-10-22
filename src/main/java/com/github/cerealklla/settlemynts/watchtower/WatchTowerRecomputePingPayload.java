package com.github.cerealklla.settlemynts.watchtower;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: "I'm standing near this Beacon, please recheck its light grid" (explicit user
 * request, 2026-10-22 -- a column skipped during {@code WatchTowerLightController#recompute}
 * because its chunk was unloaded, or whose terrain changed since, would otherwise only get fixed on
 * the Beacon's next lit/dark flip, which could be a very long time away for a continuously-fed
 * tower). Sent by {@code client.ClientWatchTowerPinger} when the player is near a Beacon it already
 * knows about (see {@link WatchTowerBeaconListPayload}). Re-validated server-side (real distance,
 * real Beacon at that position) before acting -- never trusts the client's own proximity claim,
 * same shape as every other client-triggered trigger in this mod ({@code
 * plotsign.ProximityScanPayload}'s own handler).
 */
public record WatchTowerRecomputePingPayload(BlockPos beaconPos) implements CustomPacketPayload {

    public static final Type<WatchTowerRecomputePingPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "watch_tower_recompute_ping"));

    public static final StreamCodec<ByteBuf, WatchTowerRecomputePingPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), WatchTowerRecomputePingPayload::beaconPos,
            WatchTowerRecomputePingPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
