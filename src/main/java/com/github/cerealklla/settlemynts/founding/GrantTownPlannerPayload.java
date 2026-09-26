package com.github.cerealklla.settlemynts.founding;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: grants Town Planner permission to an online player by name, from {@code
 * client.FoundingScreen} (design doc Section 6). Founder-only -- {@code
 * GhostTownHallCoreEntity#grantTownPlanner} enforces this server-side regardless of what the
 * client sends. Target-by-online-name only is a known v1 limitation (see decisions.md) -- granting
 * to an offline player isn't supported yet.
 */
public record GrantTownPlannerPayload(int coreEntityId, String targetPlayerName) implements CustomPacketPayload {

    public static final Type<GrantTownPlannerPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "grant_town_planner"));

    public static final StreamCodec<ByteBuf, GrantTownPlannerPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, GrantTownPlannerPayload::coreEntityId,
            ByteBufCodecs.STRING_UTF8, GrantTownPlannerPayload::targetPlayerName,
            GrantTownPlannerPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
