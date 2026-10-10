package com.github.cerealklla.settlemynts.plotsign;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server-to-client: the "Merchant Gold: # / Player Gold: #" totals on an already-open {@code
 * client.ShopScreen} just changed (real report, 2026-10-10: "After making a purchase in the shop the
 * gold totals up top don't update" -- {@link OpenShopPayload}'s own two gold fields are a one-time
 * snapshot from whenever the screen was opened, by design, and that screen never re-opens itself
 * after a Buy/Sell). Sent right after a successful {@code BuyFromShopPayload}/{@code
 * SellToShopPayload} completes, with both totals freshly recomputed the same way {@code
 * SettlemyntsMod#requestShop} does. Deliberately not a full re-send of {@link OpenShopPayload} --
 * that would also need to rebuild every listing/stock row for no real benefit, and the client's own
 * "open a shop screen" poll-once handoff only ever acts while no screen is already open (see {@code
 * SettlemyntsModClient}), so it would silently do nothing while the Shop screen itself is what's
 * open -- exactly the case this needs to handle.
 */
public record ShopGoldUpdatePayload(int shopGoldNuggets, int playerGoldNuggets) implements CustomPacketPayload {

    public static final Type<ShopGoldUpdatePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "shop_gold_update"));

    public static final StreamCodec<ByteBuf, ShopGoldUpdatePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ShopGoldUpdatePayload::shopGoldNuggets,
            ByteBufCodecs.VAR_INT, ShopGoldUpdatePayload::playerGoldNuggets,
            ShopGoldUpdatePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
