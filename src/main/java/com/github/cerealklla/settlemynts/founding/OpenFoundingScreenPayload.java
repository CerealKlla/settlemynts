package com.github.cerealklla.settlemynts.founding;

import java.util.ArrayList;
import java.util.List;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server-to-client: opens {@code client.FoundingScreen} for a {@code GhostTownHallCoreEntity} a
 * Town Planner just interacted with (design doc Section 6). Carries a server-precomputed snapshot
 * (current name, resolved Town Planner names, whether the viewer is the founder) so the client
 * makes no further server queries of its own to render the screen -- same shape as Lyfe's
 * {@code OpenWritingScreenPayload}.
 */
public record OpenFoundingScreenPayload(int coreEntityId, String settlementName, List<String> townPlannerNames, boolean viewerIsFounder)
        implements CustomPacketPayload {

    public static final Type<OpenFoundingScreenPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "open_founding_screen"));

    public static final StreamCodec<ByteBuf, OpenFoundingScreenPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, OpenFoundingScreenPayload::coreEntityId,
            ByteBufCodecs.STRING_UTF8, OpenFoundingScreenPayload::settlementName,
            ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8), OpenFoundingScreenPayload::townPlannerNames,
            ByteBufCodecs.BOOL, OpenFoundingScreenPayload::viewerIsFounder,
            OpenFoundingScreenPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
