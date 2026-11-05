package com.github.cerealklla.settlemynts.plotsign;

import java.util.List;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server, Radio tab: "Save Playlist" -- one batch covering the whole selected track-id
 * set, same "save everything at once" convention as {@link SetShopListingsPayload}.
 */
public record SetMurmurPlaylistPayload(BlockPos signPos, List<String> trackIds) implements CustomPacketPayload {

    public static final Type<SetMurmurPlaylistPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "set_murmur_playlist"));

    public static final StreamCodec<ByteBuf, SetMurmurPlaylistPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), SetMurmurPlaylistPayload::signPos,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), SetMurmurPlaylistPayload::trackIds,
            SetMurmurPlaylistPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
