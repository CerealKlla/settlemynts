package com.github.cerealklla.settlemynts.guardhouse;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BannerPatternLayers;

/**
 * Server-to-client: this guard's current cloak banner pattern (backlog item 12) -- {@code
 * BannerPatternLayers} has no {@code EntityDataSerializer}, so unlike {@link
 * GuardEntity#cloakColorName}/{@code showsCloakBanner} (plain {@code SynchedEntityData}), this one
 * field needs its own payload, the same way {@code founding.SetSettlementBannerPayload} already
 * does. Sent by {@link GuardEntity#refreshCloakState} (broadcast to current trackers, only when the
 * patterns actually changed) and again by {@link GuardEntity#startSeenByPlayer} for any new tracker,
 * since a tracking-start carries no automatic replay of a previously-sent custom payload the way
 * {@code SynchedEntityData} itself gets for free.
 */
public record GuardCloakBannerPayload(int entityId, BannerPatternLayers patterns) implements CustomPacketPayload {

    public static final Type<GuardCloakBannerPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "guard_cloak_banner"));

    public static final StreamCodec<RegistryFriendlyByteBuf, GuardCloakBannerPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, GuardCloakBannerPayload::entityId,
            BannerPatternLayers.STREAM_CODEC, GuardCloakBannerPayload::patterns,
            GuardCloakBannerPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
