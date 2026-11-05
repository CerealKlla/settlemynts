package com.github.cerealklla.settlemynts.bridge;

import java.util.List;

import com.github.cerealklla.audyble.api.Audyble;

import net.neoforged.fml.ModList;

/**
 * Settlemynts-side bridge for the Murmur Tower Radio tab -- Settlemynts -> Audyble direction (the
 * Plot Config Sign GUI lives here, but the real track/station data lives in Audyble), same
 * isolation convention as {@link AudybleIdentityBridge}: every call site elsewhere in this mod must
 * check {@link #isLoaded()} first, and this class's own public method signatures never leak an
 * Audyble type out (see {@link CatalogTrack}, a local copy of {@code voice.MusicLibrary.TrackSummary}).
 */
public final class AudybleMusicBridge {

    private AudybleMusicBridge() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded("audyble");
    }

    public record CatalogTrack(String trackId, String artist, String song) {
    }

    public static List<CatalogTrack> listCatalog() {
        return Audyble.listCatalogTracks().stream()
                .map(t -> new CatalogTrack(t.trackId(), t.artist(), t.song()))
                .toList();
    }

    public static List<String> getStationPlaylist(long settlementPlaceId) {
        return Audyble.getStationPlaylist(settlementPlaceId);
    }

    public static void setStationPlaylist(long settlementPlaceId, List<String> trackIds) {
        Audyble.setStationPlaylist(settlementPlaceId, trackIds);
    }

    public static void submitSongRequest(long settlementPlaceId, String url, String requesterName) {
        Audyble.appendSongRequest(settlementPlaceId, url, requesterName);
    }

    public static boolean songRequestsEnabled() {
        return Audyble.songRequestsEnabled();
    }
}
