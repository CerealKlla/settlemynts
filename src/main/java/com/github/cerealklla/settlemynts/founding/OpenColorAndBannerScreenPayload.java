package com.github.cerealklla.settlemynts.founding;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BannerPatternLayers;

/**
 * Server-to-client: opens {@code client.ColorAndBannerScreen} -- originally backlog item 10's
 * Capital-City-only color/banner menu, widened the same day (2026-11-13) for backlog item 11 to
 * also serve a regular settlement's Town Hall Core, same payload/screen either way. {@code
 * isCapitalCity} is what the screen uses to decide whether to show the Kingdom Banner row at all
 * (Capital City only, per item 10 -- a regular settlement has no such field) and to label things
 * ("Capital City Keep"/"Capital City Color" vs. "Settlement Hall"/"Settlement Color"). {@code
 * colorName} is {@code DyeColor#getName()}, or empty if the Mayor hasn't set one yet (see {@code
 * GhostTownHallCoreEntity#settlementColor}'s own doc for why a plain string rather than a custom
 * {@code Optional<DyeColor>} codec -- same reasoning {@code zone.RequiredTags}' NBT storage already
 * uses). {@code townHallTier} gates which controls the screen shows as active -- resolved
 * server-side from {@code GhostTownHallCoreEntity#townHallPlot} at request time, not synced
 * continuously, since this screen is only ever opened on demand.
 */
public record OpenColorAndBannerScreenPayload(int coreEntityId, boolean isCapitalCity, int townHallTier, String colorName,
                                                BannerPatternLayers settlementBannerPatterns,
                                                BannerPatternLayers kingdomBannerPatterns)
        implements CustomPacketPayload {

    public static final Type<OpenColorAndBannerScreenPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "open_color_and_banner_screen"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenColorAndBannerScreenPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, OpenColorAndBannerScreenPayload::coreEntityId,
            ByteBufCodecs.BOOL, OpenColorAndBannerScreenPayload::isCapitalCity,
            ByteBufCodecs.VAR_INT, OpenColorAndBannerScreenPayload::townHallTier,
            ByteBufCodecs.STRING_UTF8, OpenColorAndBannerScreenPayload::colorName,
            BannerPatternLayers.STREAM_CODEC, OpenColorAndBannerScreenPayload::settlementBannerPatterns,
            BannerPatternLayers.STREAM_CODEC, OpenColorAndBannerScreenPayload::kingdomBannerPatterns,
            OpenColorAndBannerScreenPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
