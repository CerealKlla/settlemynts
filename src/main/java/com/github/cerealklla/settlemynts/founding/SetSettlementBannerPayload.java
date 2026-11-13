package com.github.cerealklla.settlemynts.founding;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BannerPatternLayers;

/**
 * Client-to-server: commits a freshly-designed Settlement Banner (originally backlog item 10's
 * Capital-City-only "Capital City Banner" payload, widened the same day, 2026-11-13, for backlog
 * item 11's regular-settlement Tier 3+ gate) -- sent once by {@code client.BannerDesignerScreen}'s
 * "Save" button, carrying the complete, client-built draft layer list. Gated server-side (Tier 2+
 * for a Capital City, Tier 3+ for a regular settlement -- never trusted from the client's own
 * button-active state).
 */
public record SetSettlementBannerPayload(int coreEntityId, BannerPatternLayers patterns) implements CustomPacketPayload {

    public static final Type<SetSettlementBannerPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "set_settlement_banner"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetSettlementBannerPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetSettlementBannerPayload::coreEntityId,
            BannerPatternLayers.STREAM_CODEC, SetSettlementBannerPayload::patterns,
            SetSettlementBannerPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
