package com.github.cerealklla.settlemynts.plotsign;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: "Establish Highway" button click on the Town Hall's own Plot Config Sign Main
 * Menu -- added 2026-10-13. The server resolves the real candidate destination list (every
 * settlement the Mayor has personal knowledge of, including partial knowledge, within 2 real miles,
 * via {@code bridge.LyfeKnownSettlementsBridge}) and sends back {@link EstablishHighwayCandidatesPayload}
 * to open {@code client.EstablishHighwayMapScreen}. No cost/permission check happens here -- just
 * resolving candidates to show; the real re-validation (founder-only, 1000 Gold Nuggets, settlement
 * pair still free) happens at {@link RequestCreateHighwayQuestPayload}, same "never trust the
 * client" discipline as every other sign action in this mod.
 */
public record RequestEstablishHighwayMapPayload(BlockPos signPos) implements CustomPacketPayload {

    public static final Type<RequestEstablishHighwayMapPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "request_establish_highway_map"));

    public static final StreamCodec<ByteBuf, RequestEstablishHighwayMapPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), RequestEstablishHighwayMapPayload::signPos,
            RequestEstablishHighwayMapPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
