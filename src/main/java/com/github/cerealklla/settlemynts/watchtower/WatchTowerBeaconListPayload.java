package com.github.cerealklla.settlemynts.watchtower;

import java.util.ArrayList;
import java.util.List;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.GlobalPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server-to-client: every known Watch Tower Beacon's position (the whole, tiny
 * {@link WatchTowerIndex}), sent on login and re-sent to everyone whenever a new Beacon
 * self-resolves its identity -- see {@code client.ClientWatchTowerPinger}'s own doc for why the
 * client needs this at all (it lets a client check distance to a known Beacon with plain math,
 * instead of scanning blocks/entities over a wide radius every tick the way the shop-proximity
 * scanner already learned not to -- see {@code plotsign.PlotShopProximityTicker}'s own doc on that).
 */
public record WatchTowerBeaconListPayload(List<GlobalPos> beacons) implements CustomPacketPayload {

    public static final Type<WatchTowerBeaconListPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "watch_tower_beacon_list"));

    public static final StreamCodec<ByteBuf, WatchTowerBeaconListPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.fromCodec(GlobalPos.CODEC)), WatchTowerBeaconListPayload::beacons,
            WatchTowerBeaconListPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
