package com.github.cerealklla.settlemynts.founding;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server-to-client: opens {@code client.StakeScreen} for a {@code GhostPerimeterStakeEntity} a
 * Town Planner just interacted with (design doc Section 6). {@code absoluteCount}/{@code
 * maxAbsolute} let the screen show "3/5" and disable the toggle at the cap without a further
 * server round-trip just to display that.
 */
public record OpenStakeScreenPayload(int stakeEntityId, boolean absolute, int absoluteCount, int maxAbsolute)
        implements CustomPacketPayload {

    public static final Type<OpenStakeScreenPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "open_stake_screen"));

    public static final StreamCodec<ByteBuf, OpenStakeScreenPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, OpenStakeScreenPayload::stakeEntityId,
            ByteBufCodecs.BOOL, OpenStakeScreenPayload::absolute,
            ByteBufCodecs.VAR_INT, OpenStakeScreenPayload::absoluteCount,
            ByteBufCodecs.VAR_INT, OpenStakeScreenPayload::maxAbsolute,
            OpenStakeScreenPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
