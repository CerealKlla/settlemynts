package com.github.cerealklla.settlemynts.plotsign;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server-to-client: opens {@code client.PlotConfigSignMenuScreen} (design doc Section 14a's "Main
 * Menu"). {@code canManage} is resolved once, server-side ({@code PlotConfigSignBlock#useWithoutItem}
 * via {@code zone.PlotPermissions#canManage}) -- the client never re-derives permission itself, it
 * only decides which buttons to show/enable based on this. {@code hasGarrison} (added 2026-09-30) is
 * {@code true} only for a Guardhouse-typed plot -- gates the "Configure Garrison" button. {@code
 * hasConstructionBox} (added 2026-10-09) gates the new "Upgrade Plot" button -- {@code true}
 * whenever this plot has a Building Supply Box at all (every plot type, including Town Hall/
 * Guardhouse, can have one), regardless of its current funding/completion state (the upgrade
 * request itself is re-validated server-side either way). {@code tier} (added the same day) is this
 * plot's own current unlocked construction-Tier cap ({@code zone.PlotRecord#tier}) -- sent along so
 * {@code client.UpgradePlotScreen} can compute/display the next Tier's cost without a server
 * round-trip (the same pure, client-computable {@code construction.PlotTierUpgradeCost} table the
 * server itself charges against). {@code maxPlotTier} (added 2026-10-09, explicit request: "I'd like
 * the Town Hall Plot Tier to be the limit for individual Plot Tiers") is 5 for the Town Hall plot
 * itself, or the settlement's Town Hall plot's own current {@code tier} for every other plot -- gates
 * whether "Upgrade Plot" is even shown.
 */
public record OpenPlotConfigSignMenuPayload(BlockPos signPos, boolean canManage, boolean hasGarrison, boolean isTownHall,
                                             boolean hasConstructionBox, int tier, int maxPlotTier) implements CustomPacketPayload {

    public static final Type<OpenPlotConfigSignMenuPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "open_plot_config_sign_menu"));

    public static final StreamCodec<ByteBuf, OpenPlotConfigSignMenuPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), OpenPlotConfigSignMenuPayload::signPos,
            ByteBufCodecs.BOOL, OpenPlotConfigSignMenuPayload::canManage,
            ByteBufCodecs.BOOL, OpenPlotConfigSignMenuPayload::hasGarrison,
            ByteBufCodecs.BOOL, OpenPlotConfigSignMenuPayload::isTownHall,
            ByteBufCodecs.BOOL, OpenPlotConfigSignMenuPayload::hasConstructionBox,
            ByteBufCodecs.VAR_INT, OpenPlotConfigSignMenuPayload::tier,
            ByteBufCodecs.VAR_INT, OpenPlotConfigSignMenuPayload::maxPlotTier,
            OpenPlotConfigSignMenuPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
