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
 * Server-to-client: opens the Radio tab's real content ({@code client.RadioTab}), same
 * request/reply shape as {@link OpenShopPayload}. {@code catalog} is Audyble's full Murmur Tower
 * music library (see {@code bridge.AudybleMusicBridge#listCatalog}); {@code currentPlaylist} is this
 * settlement's own already-saved picks (track ids), seeding the tab's local selection state;
 * {@code songRequestsEnabled} gates whether the "Request a Song" box is shown at all -- {@code
 * false} when Audyble's {@code murmurSongRequestFile} isn't configured.
 */
public record OpenMurmurRadioPayload(BlockPos signPos, List<CatalogEntry> catalog, List<String> currentPlaylist,
                                      boolean songRequestsEnabled) implements CustomPacketPayload {

    /** One catalog track -- mirrors Audyble's own {@code voice.MusicLibrary.TrackSummary}, translated at the one bridge call site. */
    public record CatalogEntry(String trackId, String artist, String song) {

        public static final StreamCodec<ByteBuf, CatalogEntry> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, CatalogEntry::trackId,
                ByteBufCodecs.STRING_UTF8, CatalogEntry::artist,
                ByteBufCodecs.STRING_UTF8, CatalogEntry::song,
                CatalogEntry::new);
    }

    public static final Type<OpenMurmurRadioPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "open_murmur_radio"));

    public static final StreamCodec<ByteBuf, OpenMurmurRadioPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), OpenMurmurRadioPayload::signPos,
            CatalogEntry.STREAM_CODEC.apply(ByteBufCodecs.list()), OpenMurmurRadioPayload::catalog,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), OpenMurmurRadioPayload::currentPlaylist,
            ByteBufCodecs.BOOL, OpenMurmurRadioPayload::songRequestsEnabled,
            OpenMurmurRadioPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
