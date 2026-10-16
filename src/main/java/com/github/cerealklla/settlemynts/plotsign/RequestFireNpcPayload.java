package com.github.cerealklla.settlemynts.plotsign;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: "Fire NPC" on {@code client.NpcMenuScreen} -- explicit user spec, 2026-10-16:
 * "Only on owned plots, visible only to owner." A one-shot action, same convention as "Hire NPC" --
 * no confirmation screen, immediately clears {@code zone.PlotRecord#npcHired} and discards the
 * current worker (see {@code SettlemyntsMod#requestFireNpc}). Unlike a funds-driven despawn, this
 * doesn't leave {@code npcHired} standing -- the owner has to click "Hire NPC" again to re-staff,
 * same as if they'd never hired one.
 */
public record RequestFireNpcPayload(ShopAnchor.Npc anchor) implements CustomPacketPayload {

    public static final Type<RequestFireNpcPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "request_fire_npc"));

    public static final StreamCodec<ByteBuf, RequestFireNpcPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, payload -> payload.anchor().entityId(),
            entityId -> new RequestFireNpcPayload(new ShopAnchor.Npc(entityId)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
