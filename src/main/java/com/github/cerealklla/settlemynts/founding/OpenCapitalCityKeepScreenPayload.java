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
 * Server-to-client: opens {@code client.CapitalCityKeepScreen} (backlog item 10, added
 * 2026-11-13) -- the Capital City's own color/banner selection menu, same payload-snapshot shape
 * every other screen in this family uses. {@code colorName} is {@code DyeColor#getName()}, or
 * empty if the Mayor hasn't set one yet (see {@code GhostTownHallCoreEntity#settlementColor}'s own
 * doc for why a plain string rather than a custom {@code Optional<DyeColor>} codec -- same
 * reasoning {@code zone.RequiredTags}' NBT storage already uses). {@code townHallTier} gates which
 * of the two "Design ... Banner" buttons the screen shows as active (Tier 2+/3+ respectively) --
 * resolved server-side from {@code GhostTownHallCoreEntity#townHallPlot} at request time, not
 * synced continuously, since this screen is only ever opened on demand.
 */
public record OpenCapitalCityKeepScreenPayload(int coreEntityId, int townHallTier, String colorName,
                                                 BannerPatternLayers capitalCityBannerPatterns,
                                                 BannerPatternLayers kingdomBannerPatterns)
        implements CustomPacketPayload {

    public static final Type<OpenCapitalCityKeepScreenPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "open_capital_city_keep_screen"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenCapitalCityKeepScreenPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, OpenCapitalCityKeepScreenPayload::coreEntityId,
            ByteBufCodecs.VAR_INT, OpenCapitalCityKeepScreenPayload::townHallTier,
            ByteBufCodecs.STRING_UTF8, OpenCapitalCityKeepScreenPayload::colorName,
            BannerPatternLayers.STREAM_CODEC, OpenCapitalCityKeepScreenPayload::capitalCityBannerPatterns,
            BannerPatternLayers.STREAM_CODEC, OpenCapitalCityKeepScreenPayload::kingdomBannerPatterns,
            OpenCapitalCityKeepScreenPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
