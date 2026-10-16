package com.github.cerealklla.settlemynts.plotsign;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server-to-client: "a shop/quest-eligible NPC is (or is no longer) within range" -- drives {@code
 * client.ShopPromptOverlay}/{@code client.ClientShopPromptState} (design doc Section 14a, added
 * 2026-10-05: "when a player enters within 4 blocks of a Plot Sign, floating text centered over
 * their XP bar needs to show 'Press &lt;hotkey&gt; to open shop'"). Sent only on change by {@link
 * PlotShopProximityTicker}, not every scan -- {@code present} false means "nothing nearby anymore,"
 * in which case {@code anchor} is meaningless.
 *
 * <p><b>Reworked 2026-10-16</b> -- the NPC Menu overhaul (explicit user spec) retired the old
 * two-key, two-line "Press G to open shop" / "Press H to chat" prompt in favor of one key (G) and
 * one prompt line opening a single menu with every option an NPC actually offers -- see {@code
 * SettlemyntsModClient}'s own doc and {@code client.NpcMenuScreen}. {@code hasWishlist} (the old
 * second-line gate) is gone; this payload is now just "is anything here at all." Also narrowed to
 * NPC anchors only (a Plot Config Sign is reached by right-clicking the physical sign directly,
 * which was never gated by this prompt to begin with) -- see {@link PlotShopProximityTicker}'s own
 * doc for why Sign candidates are no longer considered here at all.
 */
public record PlotShopPromptPayload(boolean present, ShopAnchor anchor) implements CustomPacketPayload {

    public static final Type<PlotShopPromptPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "plot_shop_prompt"));

    public static final StreamCodec<ByteBuf, PlotShopPromptPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, PlotShopPromptPayload::present,
            ShopAnchor.STREAM_CODEC, PlotShopPromptPayload::anchor,
            PlotShopPromptPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
