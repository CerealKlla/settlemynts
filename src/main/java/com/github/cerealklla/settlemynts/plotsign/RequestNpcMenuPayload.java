package com.github.cerealklla.settlemynts.plotsign;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: "G" or right-clicking an NPC -- the NPC Menu overhaul, explicit user spec,
 * 2026-10-16. Replaces the old direct {@code RequestShopPayload(anchor, false)} (G) / {@code
 * RequestShopWishlistPayload(anchor)} (H) dispatch with a single request that always opens {@code
 * client.NpcMenuScreen} first; that screen's own buttons are what send the familiar payloads
 * afterward. See {@code SettlemyntsMod#requestNpcMenu}/{@code computeNpcMenuOptions}.
 */
public record RequestNpcMenuPayload(ShopAnchor.Npc anchor) implements CustomPacketPayload {

    public static final Type<RequestNpcMenuPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "request_npc_menu"));

    public static final StreamCodec<ByteBuf, RequestNpcMenuPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, payload -> payload.anchor().entityId(),
            entityId -> new RequestNpcMenuPayload(new ShopAnchor.Npc(entityId)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
