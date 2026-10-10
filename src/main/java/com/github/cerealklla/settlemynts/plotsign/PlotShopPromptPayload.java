package com.github.cerealklla.settlemynts.plotsign;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server-to-client: "a shop-eligible Plot Config Sign (or, added 2026-10-08, a natural village's
 * trading Villager) is (or is no longer) within range" -- drives {@code
 * client.ShopPromptOverlay}/{@code client.ClientShopPromptState} (design doc Section 14a, added
 * 2026-10-05: "when a player enters within 4 blocks of a Plot Sign, floating text centered over
 * their XP bar needs to show 'Press &lt;hotkey&gt; to open shop'"). Sent only on change by {@link
 * PlotShopProximityTicker}, not every scan -- {@code present} false means "nothing nearby anymore,"
 * in which case {@code anchor}/{@code hasWishlist} are meaningless.
 *
 * <p>{@code hasWishlist} (added 2026-10-10, see {@code zone.ShopWishlist}'s own doc) is whether this
 * shop currently has any unmet Planned Inventory deficit -- drives the second "Press &lt;hotkey&gt;
 * to chat" prompt line, shown only when there's actually something to ask about.
 */
public record PlotShopPromptPayload(boolean present, ShopAnchor anchor, boolean hasWishlist) implements CustomPacketPayload {

    public static final Type<PlotShopPromptPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "plot_shop_prompt"));

    public static final StreamCodec<ByteBuf, PlotShopPromptPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, PlotShopPromptPayload::present,
            ShopAnchor.STREAM_CODEC, PlotShopPromptPayload::anchor,
            ByteBufCodecs.BOOL, PlotShopPromptPayload::hasWishlist,
            PlotShopPromptPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
