package com.github.cerealklla.settlemynts.plotsign;

import java.util.List;
import java.util.UUID;

import io.netty.buffer.ByteBuf;

import com.mojang.serialization.Codec;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server-to-client: opens {@code client.ShopScreen} (buy mode) or {@code client.ManageShopScreen}
 * (manage mode) -- design doc Section 14a, 2026-10-05, split into two screens 2026-10-08. {@code
 * manage} mirrors the request and picks which screen opens. {@code listings} (buy mode) is every
 * currently-listed resource with its live price and stock; {@code inventory} (manage mode, added
 * 2026-10-08) is every unique item physically sitting in the plot's boxes right now, plus any
 * existing listing not currently backed by box stock -- see {@code ShopInventoryEntry}'s own doc.
 * Only one of the two lists is ever populated for a given reply (the other is empty) since a single
 * request is always either buy or manage, never both.
 *
 * <p>{@code shopGoldNuggets}/{@code playerGoldNuggets} (added 2026-10-08, explicit request: "can we
 * also list 'Merchant Gold: #' and 'Player Gold: #' somewhere?") -- the plot's own Gold Nugget stock
 * (how much the shop itself has on hand to pay for a "Sell," a plain count out of {@code
 * api.Settlemynts#scanPlotItemStock}) and the requesting player's own balance ({@code
 * bridge.YconomicsShopBridge#getNuggetBalance}, loose inventory + Coin Purse). Computed for both
 * modes alike (cheap either way) but only rendered by {@code client.ShopScreen} (buy mode) today.
 */
public record OpenShopPayload(ShopAnchor anchor, UUID plotId, boolean manage, List<ShopListingEntry> listings,
                               List<ShopInventoryEntry> inventory, int shopGoldNuggets, int playerGoldNuggets) implements CustomPacketPayload {

    public static final Type<OpenShopPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "open_shop"));

    private static final Codec<List<ShopListingEntry>> LISTINGS_CODEC = ShopListingEntry.CODEC.listOf();
    private static final Codec<List<ShopInventoryEntry>> INVENTORY_CODEC = ShopInventoryEntry.CODEC.listOf();

    public static final StreamCodec<ByteBuf, OpenShopPayload> STREAM_CODEC = StreamCodec.composite(
            ShopAnchor.STREAM_CODEC, OpenShopPayload::anchor,
            ByteBufCodecs.fromCodec(UUIDUtil.CODEC), OpenShopPayload::plotId,
            ByteBufCodecs.BOOL, OpenShopPayload::manage,
            ByteBufCodecs.fromCodec(LISTINGS_CODEC), OpenShopPayload::listings,
            ByteBufCodecs.fromCodec(INVENTORY_CODEC), OpenShopPayload::inventory,
            ByteBufCodecs.VAR_INT, OpenShopPayload::shopGoldNuggets,
            ByteBufCodecs.VAR_INT, OpenShopPayload::playerGoldNuggets,
            OpenShopPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
