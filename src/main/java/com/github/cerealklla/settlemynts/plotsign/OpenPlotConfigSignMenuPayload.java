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
 *
 * <p>{@code canHireNpc} (added 2026-10-15) gates the new "Hire NPC" button -- {@code true} only for
 * a plot the player can manage, that's player-owned (an NPC-owned plot already gets a free NPC),
 * and isn't a Guardhouse or Town Hall (neither ever spawns the kind of worker this button hires --
 * see {@code resident.ResidentSpawnTicker}'s own Guardhouse/zone-type exclusions). See {@code
 * zone.NpcHireMaintenance}/{@code zone.NpcMaintenanceTicker} for the full feature.
 *
 * <p>{@code guardKitStandsAvailable} (added 2026-10-16, Guard Kit Stand overhaul) -- {@code
 * guardhouse.GuardhouseConstants#capacityForTier(tier) - plot.guardKitStandsIssued()} for a
 * Guardhouse-typed plot, {@code 0} otherwise. Gates/labels the new "Get Guard Kit Stands" button --
 * only shown when {@code > 0} (alongside the existing {@code canManage}/{@code hasGarrison} gate).
 *
 * <p>{@code isMineshaft} (added 2026-10-23, Mineshaft Specialization + tabbed Plot Config Sign
 * rework) -- {@code true} only for a Mineshaft-typed plot ({@code
 * zone.PlotWorkerKind#MINESHAFT_ZONE_TYPE_ID}). Gates the new "Specialization" (Ore/Stone) control
 * inside {@code client.ConfigureTab}.
 *
 * <p>{@code isFarm}/{@code isLumberyard} (added 2026-10-28, Plant Selection, "similar way to the
 * Mine") -- gate the new per-slot Seed/Sapling Plant Selection control inside {@code
 * client.ConfigureTab}, same shape as {@code isMineshaft}'s own gate.
 */
public record OpenPlotConfigSignMenuPayload(BlockPos signPos, boolean canManage, boolean hasGarrison, boolean isTownHall,
                                             boolean hasConstructionBox, int tier, int maxPlotTier, boolean canHireNpc,
                                             int guardKitStandsAvailable, boolean isMineshaft, boolean isFarm,
                                             boolean isLumberyard) implements CustomPacketPayload {

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
            ByteBufCodecs.BOOL, OpenPlotConfigSignMenuPayload::canHireNpc,
            ByteBufCodecs.VAR_INT, OpenPlotConfigSignMenuPayload::guardKitStandsAvailable,
            ByteBufCodecs.BOOL, OpenPlotConfigSignMenuPayload::isMineshaft,
            ByteBufCodecs.BOOL, OpenPlotConfigSignMenuPayload::isFarm,
            ByteBufCodecs.BOOL, OpenPlotConfigSignMenuPayload::isLumberyard,
            OpenPlotConfigSignMenuPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
