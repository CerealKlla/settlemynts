package com.github.cerealklla.settlemynts.plotsign;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client-to-server: a "Buy N" click on {@code client.ShopScreen}. Server charges the buyer's own Gold Nugget balance (loose inventory, then Coin Purse) only for however much was actually filled. */
public record BuyFromShopPayload(BlockPos signPos, Identifier resourceKey, boolean isTag, int quantity) implements CustomPacketPayload {

    public static final Type<BuyFromShopPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "buy_from_shop"));

    public static final StreamCodec<ByteBuf, BuyFromShopPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), BuyFromShopPayload::signPos,
            ByteBufCodecs.fromCodec(Identifier.CODEC), BuyFromShopPayload::resourceKey,
            ByteBufCodecs.BOOL, BuyFromShopPayload::isTag,
            ByteBufCodecs.VAR_INT, BuyFromShopPayload::quantity,
            BuyFromShopPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
