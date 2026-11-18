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
 * Server-to-client: opens {@code client.ChangeOwnerScreen}. {@code currentOwnerName} is blank for an
 * NPC-owned plot. {@code knownPlayerNames} (see {@code zone.KnownPlayerNames}) drives the screen's
 * type-ahead suggestions as the Town Planner types -- explicit request, 2026-11-17: "change the text
 * box for owner to auto-fill with players that have logged into the server before." Submitting
 * ({@link SetPlotOwnerFromSignPayload}) re-resolves the typed name server-side independently of this
 * list (via the real {@code nameToIdCache} -- see that payload's own doc), so a name not in this
 * (MRU-capped) suggestion list can still be typed in full and accepted.
 */
public record OpenChangeOwnerScreenPayload(BlockPos signPos, String currentOwnerName, List<String> knownPlayerNames)
        implements CustomPacketPayload {

    public static final Type<OpenChangeOwnerScreenPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "open_change_owner_screen"));

    public static final StreamCodec<ByteBuf, OpenChangeOwnerScreenPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), OpenChangeOwnerScreenPayload::signPos,
            ByteBufCodecs.STRING_UTF8, OpenChangeOwnerScreenPayload::currentOwnerName,
            ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8), OpenChangeOwnerScreenPayload::knownPlayerNames,
            OpenChangeOwnerScreenPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
