package com.github.cerealklla.settlemynts.founding;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BannerPatternLayers;

/**
 * Client-to-server: commits a freshly-designed Kingdom Banner (backlog item 10, added 2026-11-13)
 * -- same shape as {@link SetCapitalCityBannerPayload}, a separate payload only because it writes
 * to a different field ({@code GhostTownHallCoreEntity#kingdomBannerPatterns}) and is gated Tier
 * 3+ instead of Tier 2+.
 */
public record SetKingdomBannerPayload(int coreEntityId, BannerPatternLayers patterns) implements CustomPacketPayload {

    public static final Type<SetKingdomBannerPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "set_kingdom_banner"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetKingdomBannerPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetKingdomBannerPayload::coreEntityId,
            BannerPatternLayers.STREAM_CODEC, SetKingdomBannerPayload::patterns,
            SetKingdomBannerPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
