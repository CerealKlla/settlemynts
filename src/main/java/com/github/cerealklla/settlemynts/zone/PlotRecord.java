package com.github.cerealklla.settlemynts.zone;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;

/**
 * A finalized plot, persisted directly on the owning {@code founding.GhostTownHallCoreEntity} --
 * design doc Section 11a. {@code plotId} is the same id the stakes were placed under while the
 * plot was still in progress ({@link PlotSessionData#plotSessionId()}), reused as the plot's
 * permanent identity rather than minting a second one.
 *
 * <p>Stores **both** of the plot's Cartographyr entity ids (the real "plot" entity and its "Town
 * Proper" buffer entity) -- needed so a future plot-destruction feature can retire both, per the
 * requirement flagged in Cartographyr's own decisions.md, 2026-09-26 ("bake in an ID so we can say
 * 'plot X is being removed, remove it from the Settlement layer, and also remove it from the
 * settlement buffer layer'"). Destruction itself isn't implemented yet.
 *
 * <p>{@code boxPos}/{@code constructionBoxId} (added 2026-09-29, revised same day) are this plot's
 * auto-placed Blueprynts **Building Supply Box** (not the unrelated Construction Site block a first
 * attempt mistakenly placed -- see decisions.md 2026-09-29 for the mix-up and correction), present
 * only if Blueprynts was loaded at Finalize time. {@code constructionBoxId} is the box's own
 * persistent Construction ID (minted by {@code api.Blueprynts#createConstructionBox}) -- the real
 * addressing key for any future cross-mod funding API, resolvable via {@code
 * Blueprynts#getConstructionBoxPos} independent of chunk-load state. {@code boxPos} is kept
 * alongside it purely for cheap display/HUD use (Lyfe, a future Settlemynts screen) without needing
 * a Blueprynts round-trip just to know where it visually is.
 *
 * <p>{@code owner} (added 2026-09-30, closing the gap flagged since 2026-09-29) -- design doc Section
 * 10's "Owner -- a player name, or an NPC (defaults to NPC)," captured on {@code PlotStakeScreen} at
 * Finalize and resolved server-side via the vanilla profile cache. {@code Optional.empty()} means
 * NPC-owned (the default, and also what a pre-2026-09-30 plot record backfills to). See {@code
 * zone.PlotPermissions#canManage} for the permission check this unlocks.
 *
 * <p>{@code billId} (added 2026-10-05) -- a player-owned plot's recurring rent bill, registered
 * against Yconomics at Finalize via {@code bridge.YconomicsBillBridge}; {@code Optional.empty()}
 * for an NPC-owned plot (design decision, same date: "NPC Owned plots will not have a recurring
 * rent") or if Yconomics wasn't loaded at Finalize time. See {@code bills.PlotRentTicker}, which
 * keeps this bill's source/destination boxes in sync with whatever's actually placed in-world.
 *
 * <p>{@code shopId} (added 2026-10-05) -- this plot's real Yconomics Shop, registered lazily by
 * {@code bridge.YconomicsShopBridge} the first time this plot's "Manage Shop" is ever opened, not
 * at Finalize (unlike {@code constructionBoxId}/{@code billId} -- a plot may never become a shop at
 * all, so there's nothing to eagerly create). {@code Optional.empty()} until then, or permanently
 * if Yconomics isn't loaded.
 *
 * <p>{@code suppressedShopResources} (added 2026-10-08) -- the plot's Shop Config's explicit "set to
 * 0/blank and saved" rows, per the design spec: "if a previous row was blank/0, then it should still
 * be blank/0, so the player doesn't have to re-ignore items every single time." See {@link
 * SuppressedShopResource}'s own class doc for why this can't just live inside a real Yconomics
 * listing. Empty by default (including every pre-existing saved plot, via the codec's default-value
 * fallback).
 *
 * <p>{@code tier} (added 2026-10-09, explicit user correction) -- this plot's own unlocked
 * construction-Tier *cap* (1-5), raised via "Upgrade Plot"'s {@code construction.PlotTierUpgradeFunding}
 * -- deliberately independent of whichever Blueprint Tier is actually bound/built on the Construction
 * Box right now: "you can upgrade the tier to tier 2 and still have a tier 1 building, as that's a
 * separate cost to upgrade and should be done on the Construction Box menus instead." Defaults to 1
 * (including every pre-existing saved plot). Pushed onto the Construction Box itself (via {@code
 * api.Blueprynts#setConstructionBoxAllowedTier}) whenever it changes, so the box's own Blueprint
 * picker knows how far up it's allowed to offer.
 */
public record PlotRecord(UUID plotId, String name, Identifier zoneTypeId, long cartographyrPlotEntityId,
                          long cartographyrBufferEntityId, Optional<BlockPos> boxPos, Optional<UUID> constructionBoxId,
                          Optional<UUID> owner, Optional<UUID> billId, Optional<UUID> shopId,
                          List<SuppressedShopResource> suppressedShopResources, int tier) {

    public static final Codec<PlotRecord> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("plot_id").forGetter(PlotRecord::plotId),
            Codec.STRING.fieldOf("name").forGetter(PlotRecord::name),
            Identifier.CODEC.fieldOf("zone_type_id").forGetter(PlotRecord::zoneTypeId),
            Codec.LONG.fieldOf("cartographyr_plot_entity_id").forGetter(PlotRecord::cartographyrPlotEntityId),
            Codec.LONG.fieldOf("cartographyr_buffer_entity_id").forGetter(PlotRecord::cartographyrBufferEntityId),
            BlockPos.CODEC.optionalFieldOf("box_pos").forGetter(PlotRecord::boxPos),
            UUIDUtil.CODEC.optionalFieldOf("construction_box_id").forGetter(PlotRecord::constructionBoxId),
            UUIDUtil.CODEC.optionalFieldOf("owner").forGetter(PlotRecord::owner),
            UUIDUtil.CODEC.optionalFieldOf("bill_id").forGetter(PlotRecord::billId),
            UUIDUtil.CODEC.optionalFieldOf("shop_id").forGetter(PlotRecord::shopId),
            SuppressedShopResource.CODEC.listOf().optionalFieldOf("suppressed_shop_resources", List.of()).forGetter(PlotRecord::suppressedShopResources),
            Codec.INT.optionalFieldOf("tier", 1).forGetter(PlotRecord::tier)
    ).apply(i, PlotRecord::new));

    /** Stamps in a freshly-registered Shop id -- see this record's own class doc on when this happens. */
    public PlotRecord withShopId(UUID shopId) {
        return new PlotRecord(plotId, name, zoneTypeId, cartographyrPlotEntityId, cartographyrBufferEntityId,
                boxPos, constructionBoxId, owner, billId, Optional.of(shopId), suppressedShopResources, tier);
    }

    /** Overwrites the full Shop Config suppression set -- see {@code SettlemyntsMod#setShopListings}, the only caller. */
    public PlotRecord withSuppressedShopResources(List<SuppressedShopResource> suppressedShopResources) {
        return new PlotRecord(plotId, name, zoneTypeId, cartographyrPlotEntityId, cartographyrBufferEntityId,
                boxPos, constructionBoxId, owner, billId, shopId, List.copyOf(suppressedShopResources), tier);
    }

    /** Raises (or sets) this plot's own unlocked construction-Tier cap -- see this record's own class doc. */
    public PlotRecord withTier(int tier) {
        return new PlotRecord(plotId, name, zoneTypeId, cartographyrPlotEntityId, cartographyrBufferEntityId,
                boxPos, constructionBoxId, owner, billId, shopId, suppressedShopResources, tier);
    }
}
