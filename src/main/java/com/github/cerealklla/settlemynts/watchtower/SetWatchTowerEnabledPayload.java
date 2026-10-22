package com.github.cerealklla.settlemynts.watchtower;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: the "Enable/Disable" toggle on {@code client.WatchTowerBeaconScreen} (explicit
 * user spec: a manual switch for the fuel-doubling bonus, to leave room for a future daylight-sensor
 * integration). Re-validated server-side via {@code WatchTowerBeaconBlockEntity#canManage} -- never
 * trusts the client's own earlier flag, same shape as {@code guardhouse.SetPreferredFoodPayload}.
 */
public record SetWatchTowerEnabledPayload(BlockPos beaconPos, boolean enabled) implements CustomPacketPayload {

    public static final Type<SetWatchTowerEnabledPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "set_watch_tower_enabled"));

    public static final StreamCodec<ByteBuf, SetWatchTowerEnabledPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), SetWatchTowerEnabledPayload::beaconPos,
            ByteBufCodecs.BOOL, SetWatchTowerEnabledPayload::enabled,
            SetWatchTowerEnabledPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
