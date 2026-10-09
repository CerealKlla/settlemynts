package com.github.cerealklla.settlemynts.founding;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server-to-client: "while holding a Plot Placement Stake or Roadway Stake, here is the live
 * position of the stake you're currently anchored to" -- drives {@code client.StakeDistanceOverlay}'s
 * "Distance from Stake" readout (added 2026-10-09, explicit request: the same live distance readout
 * Perimeter Stakes already have from the Town Hall, but measured from whichever stake you're
 * currently attached to instead). Sent every tick alongside the existing anchor-resolution work
 * {@code SettlemyntsMod} already does for the leash-pull/carry-preview mechanics -- {@code present}
 * false means "not holding a relevant item, or no anchor yet," in which case {@code anchorPos} is
 * meaningless.
 */
public record AnchorDistancePayload(boolean present, BlockPos anchorPos) implements CustomPacketPayload {

    public static final Type<AnchorDistancePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "anchor_distance"));

    public static final StreamCodec<ByteBuf, AnchorDistancePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, AnchorDistancePayload::present,
            ByteBufCodecs.fromCodec(BlockPos.CODEC), AnchorDistancePayload::anchorPos,
            AnchorDistancePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
