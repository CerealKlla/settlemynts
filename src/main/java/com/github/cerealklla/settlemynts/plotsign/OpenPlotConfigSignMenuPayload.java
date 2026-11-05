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
 * <p>{@code zoneTypeFlags} (added 2026-10-31, replacing four separate top-level booleans --
 * {@code isMineshaft}/{@code isFarm}/{@code isLumberyard} plus the new {@code isTavern}) -- {@link
 * StreamCodec#composite} has a real fixed 12-argument-group ceiling (confirmed directly: a 13th
 * field fails to compile, "cannot infer type-variable(s)... actual and formal argument lists differ
 * in length" -- the exact same arity-limit shape {@code zone.PlotRecord}'s own 16-field {@code
 * RecordCodecBuilder} ceiling already hit once), and this payload was already sitting at that limit
 * with 12 fields before this change. A single bitmask int frees three slots at once (down to one),
 * leaving headroom for {@code isTavern} plus any future single-purpose zone-type flag without
 * needing yet another workaround. {@link #zoneFlags} packs the booleans at construction time;
 * {@link #isMineshaft()}/{@link #isFarm()}/{@link #isLumberyard()}/{@link #isTavern()} unpack them
 * with the exact same accessor names every existing call site (every {@code client.*Tab}) already
 * used before this change, so none of them needed editing.
 *
 * <p>{@code tavernStoolsAvailable} (added 2026-10-31, Tavern Plot Type, design doc Section 17) --
 * same shape as {@code guardKitStandsAvailable} above, for the new "Get Tavern Stools" button on
 * {@code client.TavernTab}.
 */
public record OpenPlotConfigSignMenuPayload(BlockPos signPos, boolean canManage, boolean hasGarrison, boolean isTownHall,
                                             boolean hasConstructionBox, int tier, int maxPlotTier, boolean canHireNpc,
                                             int guardKitStandsAvailable, int zoneTypeFlags,
                                             int tavernStoolsAvailable) implements CustomPacketPayload {

    private static final int FLAG_MINESHAFT = 1;
    private static final int FLAG_FARM = 2;
    private static final int FLAG_LUMBERYARD = 4;
    private static final int FLAG_TAVERN = 8;
    private static final int FLAG_MURMUR_TOWER = 16;

    /** Packs the five single-purpose zone-type booleans into {@link #zoneTypeFlags} -- see this record's own class doc. */
    public static int zoneFlags(boolean isMineshaft, boolean isFarm, boolean isLumberyard, boolean isTavern, boolean isMurmurTower) {
        int bits = 0;
        if (isMineshaft) bits |= FLAG_MINESHAFT;
        if (isFarm) bits |= FLAG_FARM;
        if (isLumberyard) bits |= FLAG_LUMBERYARD;
        if (isTavern) bits |= FLAG_TAVERN;
        if (isMurmurTower) bits |= FLAG_MURMUR_TOWER;
        return bits;
    }

    public boolean isMineshaft() {
        return (zoneTypeFlags & FLAG_MINESHAFT) != 0;
    }

    public boolean isFarm() {
        return (zoneTypeFlags & FLAG_FARM) != 0;
    }

    public boolean isLumberyard() {
        return (zoneTypeFlags & FLAG_LUMBERYARD) != 0;
    }

    public boolean isTavern() {
        return (zoneTypeFlags & FLAG_TAVERN) != 0;
    }

    public boolean isMurmurTower() {
        return (zoneTypeFlags & FLAG_MURMUR_TOWER) != 0;
    }

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
            ByteBufCodecs.VAR_INT, OpenPlotConfigSignMenuPayload::zoneTypeFlags,
            ByteBufCodecs.VAR_INT, OpenPlotConfigSignMenuPayload::tavernStoolsAvailable,
            OpenPlotConfigSignMenuPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
