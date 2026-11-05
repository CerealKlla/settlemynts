package com.github.cerealklla.settlemynts.plotsign;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server, Radio tab: "Submit Request" -- a pasted YouTube link. The requester's name is
 * deliberately never sent here (never trust it from the client); the server handler reads it off
 * the real connected player.
 */
public record SubmitSongRequestPayload(BlockPos signPos, String url) implements CustomPacketPayload {

    public static final Type<SubmitSongRequestPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "submit_song_request"));

    public static final StreamCodec<ByteBuf, SubmitSongRequestPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), SubmitSongRequestPayload::signPos,
            ByteBufCodecs.STRING_UTF8, SubmitSongRequestPayload::url,
            SubmitSongRequestPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
