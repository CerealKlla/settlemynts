package com.github.cerealklla.settlemynts.plotsign;

import java.util.ArrayList;
import java.util.List;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server-to-client: opens {@code client.EstablishHighwayMapScreen} -- added 2026-10-13. {@code
 * originX}/{@code originZ} are this Town Hall's own real position (the map's center); each {@link
 * Candidate} is a settlement the Mayor has personal knowledge of (visited, or partially known --
 * see {@code bridge.LyfeKnownSettlementsBridge}'s own doc) within 2 real miles, already excluding
 * any pair with an existing posted/in-progress/connected road-quest entry. {@code minStakes}/
 * {@code optimalStakeCap} are pre-computed server-side (same formula {@code RoadQuestLogic
 * #computeThresholds} already uses for every other poster) so the screen never needs its own copy
 * of that math.
 */
public record EstablishHighwayCandidatesPayload(BlockPos signPos, long originPlaceId, int originX, int originZ,
                                                 List<Candidate> candidates) implements CustomPacketPayload {

    public record Candidate(long placeId, int x, int z, String displayText, boolean fullyKnown,
                             int minStakes, int optimalStakeCap) {
        public static final StreamCodec<ByteBuf, Candidate> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, Candidate::placeId,
                ByteBufCodecs.VAR_INT, Candidate::x,
                ByteBufCodecs.VAR_INT, Candidate::z,
                ByteBufCodecs.STRING_UTF8, Candidate::displayText,
                ByteBufCodecs.BOOL, Candidate::fullyKnown,
                ByteBufCodecs.VAR_INT, Candidate::minStakes,
                ByteBufCodecs.VAR_INT, Candidate::optimalStakeCap,
                Candidate::new);
    }

    public static final Type<EstablishHighwayCandidatesPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "establish_highway_candidates"));

    public static final StreamCodec<ByteBuf, EstablishHighwayCandidatesPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), EstablishHighwayCandidatesPayload::signPos,
            ByteBufCodecs.VAR_LONG, EstablishHighwayCandidatesPayload::originPlaceId,
            ByteBufCodecs.VAR_INT, EstablishHighwayCandidatesPayload::originX,
            ByteBufCodecs.VAR_INT, EstablishHighwayCandidatesPayload::originZ,
            ByteBufCodecs.collection(ArrayList::new, Candidate.STREAM_CODEC), EstablishHighwayCandidatesPayload::candidates,
            EstablishHighwayCandidatesPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
