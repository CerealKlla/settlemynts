package com.github.cerealklla.settlemynts.plotsign;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: "Enter Shop"/"Manage Shop" on {@code client.PlotConfigSignMenuScreen}, or (added
 * 2026-10-08, {@link ShopAnchor.Npc}) right-clicking/hotkeying a natural village's own trading
 * Villager -- replaces the old inert {@code PlotShopPlaceholderPayload} (2026-10-05, see
 * decisions.md), now that a real Yconomics-backed Shop exists. Server resolves the plot behind
 * {@code anchor}, lazily registers a Shop for it on the first "Manage Shop" (see {@code
 * bridge.YconomicsShopBridge}), and replies with {@link OpenShopPayload}. {@code manage} is always
 * forced {@code false} server-side for an {@link ShopAnchor.Npc} anchor -- natural villages have no
 * owner to manage their Shop, only buy/sell.
 */
public record RequestShopPayload(ShopAnchor anchor, boolean manage) implements CustomPacketPayload {

    public static final Type<RequestShopPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "request_shop"));

    public static final StreamCodec<ByteBuf, RequestShopPayload> STREAM_CODEC = StreamCodec.composite(
            ShopAnchor.STREAM_CODEC, RequestShopPayload::anchor,
            ByteBufCodecs.BOOL, RequestShopPayload::manage,
            RequestShopPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
