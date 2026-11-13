package com.github.cerealklla.settlemynts.founding;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BannerPatternLayers;

/**
 * Client-to-server: commits a freshly-designed Capital City Banner (backlog item 10, added
 * 2026-11-13) -- sent once by {@code client.BannerDesignerScreen}'s "Save" button, carrying the
 * complete, client-built draft layer list. Gated Tier 2+ server-side (re-checked, not trusted from
 * the client's own button-active state).
 */
public record SetCapitalCityBannerPayload(int coreEntityId, BannerPatternLayers patterns) implements CustomPacketPayload {

    public static final Type<SetCapitalCityBannerPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "set_capital_city_banner"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetCapitalCityBannerPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetCapitalCityBannerPayload::coreEntityId,
            BannerPatternLayers.STREAM_CODEC, SetCapitalCityBannerPayload::patterns,
            SetCapitalCityBannerPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
