package com.github.cerealklla.settlemynts.zone;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import com.github.cerealklla.settlemynts.mineshaft.MineSpecialization;

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
 *
 * <p>{@code plannedInventory} (added 2026-10-09) -- this plot's Planned Inventory config, set on
 * {@code client.PlannedInventoryScreen}: each entry's own "happy state" target stock level for one
 * resource. Consumed by {@code zone.PlannedInventoryClearing}'s nightly inter-plot trading pass (a
 * plot below target buys from one above target, within the same settlement, at the seller's own real
 * Shop sell price). Empty by default (including every pre-existing saved plot) -- a resource only
 * ever participates once an entry exists for it; see {@link PlannedInventoryTarget}'s own class doc.
 *
 * <p>{@code tieredDefaultsAppliedTier} (added 2026-10-10) -- the highest Tier {@code
 * zone.ShopSeeding#applyTieredDefaults} has already seeded default listings/Planned Inventory targets
 * for, on a catalog with real tier progression (Armorer/Blacksmith/Restaurant, never Grocer or any
 * Blueprynts good). Lets that pass stay idempotent (only reacts once per real Tier increase, not on
 * every "Enter Shop") without needing a separate tier-change event anywhere. Defaults to 0 (including
 * every pre-existing saved plot), so a Tier 1 plot still seeds once on its very first check.
 *
 * <p>{@code hiredNpc} (added 2026-10-15/16) bundles this plot's hired-NPC configuration -- see
 * {@link HiredNpcState}'s own doc for the full story on each field. Nested as its own record rather
 * than three more top-level fields specifically because {@code PlotRecord} was already at Mojang's
 * real 16-field {@code RecordCodecBuilder} arity ceiling (confirmed directly: adding a 17th raw
 * field fails to compile at all, "cannot infer type-variable(s)... actual and formal argument lists
 * differ in length") -- same workaround Myssions' own {@code QuestInstance} already uses for the
 * identical limit ({@code RoadQuestData}/{@code EscortData}). {@link #npcHired()}/{@link
 * #npcLastChargedDay()}/{@link #job()}/{@link #withNpcHired}/{@link #withNpcLastChargedDay}/{@link
 * #withJob} are all hand-written pass-throughs onto {@code hiredNpc} with their original pre-nesting
 * signatures, so every call site written before this nesting (spawn tickers, {@code
 * NpcMaintenanceTicker}, {@code SettlemyntsMod}) kept compiling completely unchanged.
 *
 * <p>{@code guardKitStandsIssued} (added 2026-10-16, Guard Kit Stand overhaul) -- how many Guard Kit
 * Stand items this plot has ever been granted via "Get Guard Kit Stands" on the Plot Config Sign,
 * minus however many have since been permanently destroyed (a held stand item deleted outright for
 * being carried outside this plot -- see {@code SettlemyntsMod#removeStaleGuardKitStandsOutsidePlot}).
 * Picking a stand up and re-placing it anywhere inside the plot never changes this count -- it's
 * still "issued," just not currently placed. This is the live denominator against {@code
 * guardhouse.GuardhouseConstants#capacityForTier} the sign's button grants against; fills this
 * record's own last remaining slot under Mojang's real 16-field {@code RecordCodecBuilder} arity
 * ceiling (see the note above on {@code hiredNpc}) -- a single {@code int} doesn't need a nested
 * sub-record workaround the way three fields did.
 *
 * <p>{@code misc} (added 2026-10-23, Mineshaft Specialization) -- {@code PlotRecord} was already at
 * the 16-field ceiling with nothing left to shuffle, so the lone {@code tieredDefaultsAppliedTier}
 * top-level field (only 2 real external call sites -- {@code zone.ShopSeeding}, {@code
 * debug.DebugCommands}, both already using {@link #tieredDefaultsAppliedTier()}/{@link
 * #withTieredDefaultsAppliedTier}, kept unchanged) was folded into this new {@link PlotMiscState}
 * bucket alongside the new {@code mineSpecialization} field, freeing exactly one slot for the new
 * field the same way {@code hiredNpc} did for its three. Any future one-off addition to {@code
 * PlotRecord} should grow {@link PlotMiscState} the same way, not add another top-level field.
 */
public record PlotRecord(UUID plotId, String name, Identifier zoneTypeId, long cartographyrPlotEntityId,
                          long cartographyrBufferEntityId, Optional<BlockPos> boxPos, Optional<UUID> constructionBoxId,
                          Optional<UUID> owner, Optional<UUID> billId, Optional<UUID> shopId,
                          List<SuppressedShopResource> suppressedShopResources, int tier,
                          List<PlannedInventoryTarget> plannedInventory, PlotMiscState misc,
                          HiredNpcState hiredNpc, int guardKitStandsIssued) {

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
            Codec.INT.optionalFieldOf("tier", 1).forGetter(PlotRecord::tier),
            PlannedInventoryTarget.CODEC.listOf().optionalFieldOf("planned_inventory", List.of()).forGetter(PlotRecord::plannedInventory),
            PlotMiscState.CODEC.optionalFieldOf("misc", PlotMiscState.NONE).forGetter(PlotRecord::misc),
            HiredNpcState.CODEC.optionalFieldOf("hired_npc", HiredNpcState.NONE).forGetter(PlotRecord::hiredNpc),
            Codec.INT.optionalFieldOf("guard_kit_stands_issued", 0).forGetter(PlotRecord::guardKitStandsIssued)
    ).apply(i, PlotRecord::new));

    /**
     * Generic one-off-field bucket (added 2026-10-23) -- see this record's own class doc on {@code
     * misc} for why. {@code tieredDefaultsAppliedTier} moved here verbatim from its old top-level
     * slot (see {@code zone.ShopSeeding}/{@code debug.DebugCommands} for its own meaning);
     * {@code mineSpecialization} is this plot's Ore/Stone Mineshaft loot focus (ignored for any
     * non-Mineshaft plot) -- see {@link MineSpecialization}'s own doc.
     */
    public record PlotMiscState(int tieredDefaultsAppliedTier, MineSpecialization mineSpecialization, List<String> plantSelections) {
        public static final PlotMiscState NONE = new PlotMiscState(0, MineSpecialization.ORE, List.of());

        public static final Codec<PlotMiscState> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.optionalFieldOf("tiered_defaults_applied_tier", 0).forGetter(PlotMiscState::tieredDefaultsAppliedTier),
                Codec.STRING.xmap(MineSpecialization::valueOf, MineSpecialization::name)
                        .optionalFieldOf("mine_specialization", MineSpecialization.ORE).forGetter(PlotMiscState::mineSpecialization),
                Codec.STRING.listOf().optionalFieldOf("plant_selections", List.of()).forGetter(PlotMiscState::plantSelections)
        ).apply(i, PlotMiscState::new));

        public PlotMiscState withTieredDefaultsAppliedTier(int tieredDefaultsAppliedTier) {
            return new PlotMiscState(tieredDefaultsAppliedTier, mineSpecialization, plantSelections);
        }

        public PlotMiscState withMineSpecialization(MineSpecialization mineSpecialization) {
            return new PlotMiscState(tieredDefaultsAppliedTier, mineSpecialization, plantSelections);
        }

        /** {@link WorkerPlantSelection}'s own storage -- see that class's own doc for the slot shape/semantics. */
        public PlotMiscState withPlantSelections(List<String> plantSelections) {
            return new PlotMiscState(tieredDefaultsAppliedTier, mineSpecialization, plantSelections);
        }
    }

    /**
     * A player-owned plot's hired-NPC configuration (added 2026-10-15, explicit user request: "Hire
     * NPC" on the Plot Config Sign; {@code job} added 2026-10-16, "Change Job" on the new NPC Menu).
     * {@code hired} is the standing intent ("this plot should have a hired NPC"), widening {@code
     * resident.ResidentSpawnTicker}/{@code farm.FarmerSpawnTicker}/{@code
     * lumberyard.LumberjackSpawnTicker}/{@code mineshaft.MineshaftSpawnTicker}'s own {@code
     * owner().isEmpty()} eligibility check to also accept a player-owned plot with this flag set --
     * no new spawn mechanism needed, just a widened gate on the existing one. It stays {@code true}
     * through a funds-driven despawn (see {@code zone.NpcMaintenanceTicker}), so a later midnight
     * automatically retries and restores the worker once the plot is funded again, with no need to
     * re-click "Hire NPC." {@code lastChargedDay} (the real-day index, {@code
     * Math.floorDiv(overworldClockTime, 24000)}, of the last successful maintenance charge) exists
     * purely so a manual "Hire NPC" click that pays immediately doesn't ALSO get charged again by
     * that same night's midnight tick -- see {@code NpcMaintenanceTicker}'s own doc for the full
     * "Rent is pulled first, then NPC pay" ordering this needs to respect. {@code job} is this
     * plot's own explicit override of its hired NPC's worker kind (Farmer or Lumberjack), independent
     * of {@code zoneTypeId} -- {@link PlotJob#NONE} means no override; see {@link
     * PlotWorkerKind#resolve} for the single, centralized place this and {@code zoneTypeId} are
     * actually reconciled into one real worker kind. All three default to {@code false}/{@code -1}/
     * {@link PlotJob#NONE} ({@link #NONE}, including every pre-existing saved plot).
     */
    public record HiredNpcState(boolean hired, long lastChargedDay, PlotJob job) {
        public static final HiredNpcState NONE = new HiredNpcState(false, -1L, PlotJob.NONE);

        public static final Codec<HiredNpcState> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.BOOL.optionalFieldOf("hired", false).forGetter(HiredNpcState::hired),
                Codec.LONG.optionalFieldOf("last_charged_day", -1L).forGetter(HiredNpcState::lastChargedDay),
                Codec.STRING.xmap(PlotJob::valueOf, PlotJob::name).optionalFieldOf("job", PlotJob.NONE).forGetter(HiredNpcState::job)
        ).apply(i, HiredNpcState::new));

        public HiredNpcState withHired(boolean hired) {
            return new HiredNpcState(hired, lastChargedDay, job);
        }

        public HiredNpcState withLastChargedDay(long lastChargedDay) {
            return new HiredNpcState(hired, lastChargedDay, job);
        }

        public HiredNpcState withJob(PlotJob job) {
            return new HiredNpcState(hired, lastChargedDay, job);
        }
    }

    /** Pass-through onto {@link #hiredNpc}'s own {@link HiredNpcState#hired()} -- see this record's own class doc on why. */
    public boolean npcHired() {
        return hiredNpc.hired();
    }

    /** Pass-through onto {@link #hiredNpc}'s own {@link HiredNpcState#lastChargedDay()} -- see this record's own class doc on why. */
    public long npcLastChargedDay() {
        return hiredNpc.lastChargedDay();
    }

    /** Pass-through onto {@link #hiredNpc}'s own {@link HiredNpcState#job()} -- see this record's own class doc on why. */
    public PlotJob job() {
        return hiredNpc.job();
    }

    /** Pass-through onto {@link #misc}'s own {@link PlotMiscState#tieredDefaultsAppliedTier()} -- see this record's own class doc on why. */
    public int tieredDefaultsAppliedTier() {
        return misc.tieredDefaultsAppliedTier();
    }

    /** Pass-through onto {@link #misc}'s own {@link PlotMiscState#mineSpecialization()} -- see this record's own class doc on why, and {@link MineSpecialization} for what this controls. */
    public MineSpecialization mineSpecialization() {
        return misc.mineSpecialization();
    }

    public List<String> plantSelections() {
        return misc.plantSelections();
    }

    /**
     * Reassigns this plot's Owner after the fact (added 2026-10-17 for {@code debug.DebugCommands}'
     * admin-only {@code /settlemynts setplotowner} -- every other owner assignment only ever happens
     * once, at Finalize). {@code Optional.empty()} makes it NPC-owned again, same meaning as a plot
     * that was never assigned one. Callers are responsible for re-syncing anything that was computed
     * FROM the old owner (Protectyons permissions, etc.) -- this record itself has no side effects.
     */
    public PlotRecord withOwner(Optional<UUID> owner) {
        return new PlotRecord(plotId, name, zoneTypeId, cartographyrPlotEntityId, cartographyrBufferEntityId,
                boxPos, constructionBoxId, owner, billId, shopId, suppressedShopResources, tier, plannedInventory, misc, hiredNpc, guardKitStandsIssued);
    }

    /** Stamps in a freshly-registered Shop id -- see this record's own class doc on when this happens. */
    public PlotRecord withShopId(UUID shopId) {
        return new PlotRecord(plotId, name, zoneTypeId, cartographyrPlotEntityId, cartographyrBufferEntityId,
                boxPos, constructionBoxId, owner, billId, Optional.of(shopId), suppressedShopResources, tier, plannedInventory, misc, hiredNpc, guardKitStandsIssued);
    }

    /** Overwrites the full Shop Config suppression set -- see {@code SettlemyntsMod#setShopListings}, the only caller. */
    public PlotRecord withSuppressedShopResources(List<SuppressedShopResource> suppressedShopResources) {
        return new PlotRecord(plotId, name, zoneTypeId, cartographyrPlotEntityId, cartographyrBufferEntityId,
                boxPos, constructionBoxId, owner, billId, shopId, List.copyOf(suppressedShopResources), tier, plannedInventory, misc, hiredNpc, guardKitStandsIssued);
    }

    /** Raises (or sets) this plot's own unlocked construction-Tier cap -- see this record's own class doc. */
    public PlotRecord withTier(int tier) {
        return new PlotRecord(plotId, name, zoneTypeId, cartographyrPlotEntityId, cartographyrBufferEntityId,
                boxPos, constructionBoxId, owner, billId, shopId, suppressedShopResources, tier, plannedInventory, misc, hiredNpc, guardKitStandsIssued);
    }

    /** Overwrites the full Planned Inventory target set -- see {@code SettlemyntsMod#setPlannedInventory}, the only caller. */
    public PlotRecord withPlannedInventory(List<PlannedInventoryTarget> plannedInventory) {
        return new PlotRecord(plotId, name, zoneTypeId, cartographyrPlotEntityId, cartographyrBufferEntityId,
                boxPos, constructionBoxId, owner, billId, shopId, suppressedShopResources, tier, List.copyOf(plannedInventory), misc, hiredNpc, guardKitStandsIssued);
    }

    /** Marks {@code tier} as the highest Tier {@code zone.ShopSeeding#applyTieredDefaults} has seeded for -- see this record's own class doc. */
    public PlotRecord withTieredDefaultsAppliedTier(int tieredDefaultsAppliedTier) {
        return new PlotRecord(plotId, name, zoneTypeId, cartographyrPlotEntityId, cartographyrBufferEntityId,
                boxPos, constructionBoxId, owner, billId, shopId, suppressedShopResources, tier, plannedInventory, misc.withTieredDefaultsAppliedTier(tieredDefaultsAppliedTier), hiredNpc, guardKitStandsIssued);
    }

    /** "Specialization" (Configure Plot, Mineshaft only) -- switches this plot's Mineshaft loot focus between {@link MineSpecialization#ORE}/{@link MineSpecialization#STONE}/{@link MineSpecialization#EXCAVATION}. See {@link MineLootTable} and {@link MineSpecialization}'s own docs. */
    public PlotRecord withMineSpecialization(MineSpecialization mineSpecialization) {
        return new PlotRecord(plotId, name, zoneTypeId, cartographyrPlotEntityId, cartographyrBufferEntityId,
                boxPos, constructionBoxId, owner, billId, shopId, suppressedShopResources, tier, plannedInventory, misc.withMineSpecialization(mineSpecialization), hiredNpc, guardKitStandsIssued);
    }

    public PlotRecord withPlantSelections(List<String> plantSelections) {
        return new PlotRecord(plotId, name, zoneTypeId, cartographyrPlotEntityId, cartographyrBufferEntityId,
                boxPos, constructionBoxId, owner, billId, shopId, suppressedShopResources, tier, plannedInventory, misc.withPlantSelections(plantSelections), hiredNpc, guardKitStandsIssued);
    }

    /** Sets whether this plot currently has (or should have, through a funds-driven despawn) a hired
     *  NPC -- see this record's own class doc. */
    public PlotRecord withNpcHired(boolean npcHired) {
        return new PlotRecord(plotId, name, zoneTypeId, cartographyrPlotEntityId, cartographyrBufferEntityId,
                boxPos, constructionBoxId, owner, billId, shopId, suppressedShopResources, tier, plannedInventory, misc, hiredNpc.withHired(npcHired), guardKitStandsIssued);
    }

    /** Records the real-day index of this plot's last successful NPC maintenance charge -- see this
     *  record's own class doc for why this exists (avoiding a same-day double-charge between a
     *  manual "Hire NPC" click and that night's midnight tick). */
    public PlotRecord withNpcLastChargedDay(long npcLastChargedDay) {
        return new PlotRecord(plotId, name, zoneTypeId, cartographyrPlotEntityId, cartographyrBufferEntityId,
                boxPos, constructionBoxId, owner, billId, shopId, suppressedShopResources, tier, plannedInventory, misc, hiredNpc.withLastChargedDay(npcLastChargedDay), guardKitStandsIssued);
    }

    /** "Change Job" -- overrides which worker kind this plot's hired NPC should be, independent of
     *  {@code zoneTypeId}. See this record's own class doc and {@link PlotWorkerKind}. */
    public PlotRecord withJob(PlotJob job) {
        return new PlotRecord(plotId, name, zoneTypeId, cartographyrPlotEntityId, cartographyrBufferEntityId,
                boxPos, constructionBoxId, owner, billId, shopId, suppressedShopResources, tier, plannedInventory, misc, hiredNpc.withJob(job), guardKitStandsIssued);
    }

    /** Adjusts how many Guard Kit Stands this plot has ever been issued -- see this record's own class
     *  doc. {@code SettlemyntsMod}'s "Get Guard Kit Stands" handler passes {@code +1} per grant; the
     *  outside-plot removal check passes {@code -1} per stand permanently destroyed. */
    public PlotRecord withGuardKitStandsIssued(int guardKitStandsIssued) {
        return new PlotRecord(plotId, name, zoneTypeId, cartographyrPlotEntityId, cartographyrBufferEntityId,
                boxPos, constructionBoxId, owner, billId, shopId, suppressedShopResources, tier, plannedInventory, misc, hiredNpc, guardKitStandsIssued);
    }
}
