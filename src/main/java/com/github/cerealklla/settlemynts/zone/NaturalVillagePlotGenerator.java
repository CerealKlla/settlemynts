package com.github.cerealklla.settlemynts.zone;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.github.cerealklla.cartographyr.api.Cartography;
import com.github.cerealklla.cartographyr.geo.Classification;
import com.github.cerealklla.cartographyr.geo.EntityDefinition;
import com.github.cerealklla.cartographyr.geo.EntityId;
import com.github.cerealklla.cartographyr.geo.GeographicEntity;
import com.github.cerealklla.cartographyr.geo.Geometry;
import com.github.cerealklla.cartographyr.geo.LifecycleState;
import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.AABB;

/**
 * Carves one {@link PlotRecord} per structure piece out of a naturally-discovered village, the first
 * time Settlemynts notices that village exists (see {@link NaturalVillagePlotListener}) -- design
 * plan "Natural settlements: minimap perimeters, auto-plots with zone inference, NPC respawn," Part
 * B, 2026-10-08. Plots are **full** {@link PlotRecord}s (real {@link ZoneType}, real Cartographyr
 * plot + buffer entities, a real Shop where the zone type has a catalog) -- the one deliberate
 * omission, per explicit user direction, is no physical Plot Config Sign or Blueprynts Construction
 * Box: these settlements aren't meant to be built/managed by players the way a founded settlement is.
 * {@code PlotRecord#owner} always stays {@code Optional.empty()} (NPC-owned, the existing default).
 *
 * <p>Zone inference, in priority order: (1) a nearby vanilla {@link Villager} that has already
 * self-claimed a profession, mapped to whichever Blueprynts-registered {@link ZoneType} fits
 * (Farmer -> Farm, Armorer -> Armorer, Weaponsmith/Toolsmith -> Blacksmith, Butcher -> Restaurant,
 * Fisherman -> Grocer, Mason -> Stonemason -- the full mapping the user gave 2026-10-08, see {@link
 * #zoneTypeForProfession}); (2) a farmland-density heuristic
 * for a piece with no such villager yet (also covers a piece whose villager hasn't claimed a job
 * site yet, which vanilla's own AI does gradually, not at spawn -- see {@link #findNearbyVillager}'s
 * own doc); (3) a generic residential fallback (Blueprynts' "Private Residence"). If none of these
 * ids are actually registered (Blueprynts not loaded at all), the piece is skipped entirely --
 * there's no valid {@link ZoneType} to build a {@link PlotRecord} against.
 *
 * <p>Whichever villager is nearest the piece (regardless of profession) gets this plot's id stamped
 * into its persistent data (see {@link #PLOT_ID_TAG}) -- the hook {@code
 * resident.VillagerShopInteractListener} (Part C) and {@code resident.VillagerDeathListener}/{@code
 * resident.VillagerRespawnTicker} (Part D) read back. A Shop is seeded (where the Zone Type has a
 * catalog) regardless of whether any villager was actually found.
 */
public final class NaturalVillagePlotGenerator {

    public static final String PLOT_ID_TAG = "settlemynts_plot_id";

    // A piece's own bounding box is often snug around just its floor footprint -- widen the search
    // for a "resident" villager a little so one standing just outside a building's walls (a common
    // vanilla villager AI behavior) still counts.
    private static final double VILLAGER_SEARCH_PADDING_BLOCKS = 4.0;
    private static final double FARMLAND_RATIO_THRESHOLD = 0.08;

    private NaturalVillagePlotGenerator() {
    }

    /**
     * Processes every not-yet-generated piece of {@code entry} whose own chunk area is now actually
     * loaded (see {@link NaturalVillagePlotPending}'s own doc for why this is deferred rather than
     * done all at once) -- called periodically by {@code NaturalVillagePlotGenerationTicker}. Removes
     * {@code entry} from the pending queue once every piece has been processed.
     */
    static void processPending(NaturalVillagePlotPending.Entry entry) {
        ServerLevel level = entry.level();
        NaturalSettlementPlotOwner owner = new NaturalSettlementPlotOwner(level.getServer(), entry.settlement());
        List<StructurePiece> pieces = entry.start().getPieces();
        boolean[] done = entry.pieceDone();

        boolean allDone = true;
        for (int i = 0; i < pieces.size(); i++) {
            if (done[i]) {
                continue;
            }
            BoundingBox box = pieces.get(i).getBoundingBox();
            if (!isLikelyBuildingFootprint(pieces.get(i), box)) {
                done[i] = true; // Paths/fences/wells/thin decorations -- never a plot, skip for good.
                continue;
            }
            if (!chunksLoaded(level, box)) {
                allDone = false;
                continue;
            }
            generatePiece(level, owner, entry.settlement(), box);
            done[i] = true;
        }
        if (allDone) {
            NaturalVillagePlotPending.remove(entry.settlement());
            SettlemyntsMod.LOGGER.info("Natural village plot generation: finished settlement {}", entry.settlement());
        }
    }

    // Template-path filter (added 2026-10-08, replacing the original pure-geometry heuristic below
    // after a second round of live-test feedback -- "still a ton of plots that aren't actual plots").
    // Confirmed directly against this version's real vanilla data (data/minecraft/worldgen/
    // template_pool/village/<plains|desert|savanna|snowy|taiga>/*.json): every village biome's real
    // buildings (including every profession house and both farm sizes) live under a "houses/" path
    // segment; everything else a village is built from (streets/, town_centers/, zombie/streets/,
    // top-level decor pieces like lamp posts, and the villagers/ pool, which is just a bare
    // villager-spawn marker, not a building at all) never uses that segment. Far more precise than
    // guessing from a piece's raw bounding-box shape, since it reflects what the piece actually is.
    // A handful of real vanilla template names sit under /houses/ despite not being an actual
    // building -- confirmed directly against this version's own data (the "houses" pool for every
    // biome): "plains_accessory_1" is a plain flower planter, and the "meeting_point" entries
    // (duplicated under both houses/ and town_centers/ across biomes, oddly) are open-air gazebos, not
    // a dwelling -- real live-test feedback, 2026-10-08: "still classified some 1x3 flower planters as
    // residences, and some tents" (the meeting-point gazebo's open roof reads as tent-like at a
    // glance). Matched against the template's filename only, not the whole path, so this can't
    // accidentally exclude a real house whose name happens to share a path segment with one of these.
    private static final String[] NON_BUILDING_HOUSE_TEMPLATE_KEYWORDS = {"accessory", "meeting_point", "animal_pen", "stable"};

    private static boolean isLikelyBuildingFootprint(StructurePiece piece, BoundingBox box) {
        if (piece instanceof PoolElementStructurePiece poolPiece
                && poolPiece.getElement() instanceof net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement single) {
            try {
                String path = single.getTemplateLocation().getPath();
                if (!path.contains("/houses/")) {
                    return false;
                }
                String fileName = path.substring(path.lastIndexOf('/') + 1);
                for (String keyword : NON_BUILDING_HOUSE_TEMPLATE_KEYWORDS) {
                    if (fileName.contains(keyword)) {
                        return false;
                    }
                }
                return true;
            } catch (RuntimeException ignored) {
                // Either#orThrow can throw for a rare runtime-only (non-datapack) template -- fall
                // through to the geometry heuristic below rather than silently excluding it.
            }
        }
        return isLikelyBuildingFootprintByGeometry(box);
    }

    // Original pure-geometry heuristic (added 2026-10-08, first round of live-test feedback) --
    // demoted to a fallback for the rare piece that isn't a SinglePoolElement (e.g. a ListPoolElement
    // or a runtime template), since the template-path check above is far more reliable whenever it's
    // available.
    private static final int MIN_FOOTPRINT_SIDE_BLOCKS = 4;
    private static final int MIN_FOOTPRINT_AREA_BLOCKS = 20;
    private static final double MAX_FOOTPRINT_ASPECT_RATIO = 4.0;

    private static boolean isLikelyBuildingFootprintByGeometry(BoundingBox box) {
        int width = box.getXSpan();
        int depth = box.getZSpan();
        if (width < MIN_FOOTPRINT_SIDE_BLOCKS || depth < MIN_FOOTPRINT_SIDE_BLOCKS) {
            return false;
        }
        if (width * depth < MIN_FOOTPRINT_AREA_BLOCKS) {
            return false;
        }
        double longSide = Math.max(width, depth);
        double shortSide = Math.min(width, depth);
        return longSide / shortSide <= MAX_FOOTPRINT_ASPECT_RATIO;
    }

    private static boolean chunksLoaded(ServerLevel level, BoundingBox box) {
        int minChunkX = box.minX() >> 4;
        int maxChunkX = box.maxX() >> 4;
        int minChunkZ = box.minZ() >> 4;
        int maxChunkZ = box.maxZ() >> 4;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                if (!level.hasChunk(cx, cz)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void generatePiece(ServerLevel level, NaturalSettlementPlotOwner owner, SettlementKey settlement, BoundingBox box) {
        List<Geometry.Polygon.Vertex> corners = List.of(
                new Geometry.Polygon.Vertex(box.minX(), box.minZ()),
                new Geometry.Polygon.Vertex(box.minX(), box.maxZ()),
                new Geometry.Polygon.Vertex(box.maxX(), box.minZ()),
                new Geometry.Polygon.Vertex(box.maxX(), box.maxZ()));
        Geometry footprint = Geometry.Polygon.convexHull(corners);
        if (!(footprint instanceof Geometry.Polygon piecePolygon)) {
            return; // Degenerate (collinear/zero-area) piece -- same fallback SettlementDiscovery accepts, just skipped here since a plot needs a real polygon.
        }

        // Real job-site block (Composter, Blast Furnace, etc.) checked first -- see findJobSite's own
        // doc. If one exists, the villager that should be tagged is specifically whichever villager
        // has *claimed it* (vanilla's Brain-memory JOB_SITE, a real GlobalPos), not just whoever's
        // standing nearby -- a real live-test correction, 2026-10-08: tagging the nearest villager
        // regardless of job-site claim could tag a random passerby instead of the actual farmer.
        Optional<JobSite> jobSite = findJobSite(level, box);
        Optional<Villager> taggedVillager;
        Identifier zoneTypeId;
        if (jobSite.isPresent()) {
            zoneTypeId = jobSite.get().zoneTypeId();
            taggedVillager = findVillagerClaimingJobSite(level, jobSite.get().pos());
        } else {
            // No recognized job-site block in/around this piece -- fall back to "nearest villager"
            // for both zone inference (profession, if already claimed) and tagging purposes, same as
            // before.
            Optional<Villager> nearbyVillager = findNearbyVillager(level, box);
            zoneTypeId = inferZoneTypeWithoutJobSite(level, box, nearbyVillager);
            taggedVillager = nearbyVillager;
        }
        Optional<ZoneType> zoneType = ZoneTypeRegistry.get(zoneTypeId);
        SettlemyntsMod.LOGGER.info(
                "Natural village plot: piece {} -- jobSite={} taggedVillagerFound={} profession={} zoneTypeId={} zoneTypeRegistered={}",
                box, jobSite.map(JobSite::pos).orElse(null), taggedVillager.isPresent(),
                taggedVillager.map(v -> v.getVillagerData().profession().unwrapKey().map(Object::toString).orElse("?")).orElse("none"),
                zoneTypeId, zoneType.isPresent());
        if (zoneType.isEmpty()) {
            return; // No Blueprynts (or anything else) registered this id -- nothing valid to build a plot against.
        }

        Geometry.Polygon bufferPolygon = PlotGeometry.paddedBuffer(piecePolygon, SettlemyntsMod.PLOT_BUFFER_PADDING_BLOCKS);

        GeographicEntity plotEntity = Cartography.createEntity(level, new EntityDefinition(
                level.dimension(), Classification.CONSTRUCTED, SettlemyntsMod.NATURAL_PLOT_ENTITY_TYPE, SettlemyntsMod.ZONE_LAYER_ID,
                Optional.empty(), piecePolygon, LifecycleState.REALIZED, Optional.empty()));
        Cartography.setDesignation(level, plotEntity.id(), zoneType.get().label());

        GeographicEntity bufferEntity = Cartography.createEntity(level, new EntityDefinition(
                level.dimension(), Classification.CONSTRUCTED, SettlemyntsMod.PLOT_BUFFER_ENTITY_TYPE, SettlemyntsMod.ZONE_LAYER_ID,
                Optional.empty(), bufferPolygon, LifecycleState.REALIZED, Optional.empty()));

        UUID plotId = UUID.randomUUID();
        PlotRecord plotRecord = new PlotRecord(plotId, zoneType.get().label(), zoneTypeId,
                plotEntity.id().value(), bufferEntity.id().value(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), List.of());
        owner.addPlot(plotRecord);

        BlockPos anchor = box.getCenter();
        // Always attempted, independent of whether a villager was found -- a harmless no-op if this
        // Zone Type has no registered catalog (e.g. Private Residence). Previously gated behind
        // nearbyVillager.isPresent(), which meant a correctly-identified Farm piece with nobody
        // standing in it at generation time silently never got a Shop at all. Shared across every
        // plot of this Zone Type in this settlement (explicit user request, 2026-10-08) rather than
        // minting a separate Shop per building.
        if (ShopSeeding.hasCatalog(zoneTypeId)) {
            // box.getCenter()'s Y is mid-building-height (e.g. partway up a wall), not ground level --
            // box.minY() is the piece's own floor, which vanilla places at/near real terrain height.
            // See NaturalShopVault#ensureVault's own doc for the live bug this fixes.
            BlockPos vaultAnchor = new BlockPos(anchor.getX(), box.minY(), anchor.getZ());
            NaturalShopVault.ensureVault(level, settlement, zoneTypeId, vaultAnchor);
            UUID sharedShopId = resolveOrCreateSharedShop(level, settlement, zoneTypeId, plotId);
            ShopSeeding.seedNewPlotSharingShop(level, plotRecord, owner, anchor, sharedShopId);
        }
        // Villager tagging itself is NOT done here -- explicit user design, 2026-10-08: *every*
        // villager who ends up with a matching profession should be able to open this settlement's
        // shared shop, not just whichever one happened to be nearest a specific building at
        // generation time. See NaturalVillageShopLinkTicker, a continuously-running settlement-wide
        // scan that tags any (and every) villager whose profession maps to a Zone Type this
        // settlement actually has a plot (and so a Shop) for.
        if (jobSite.isEmpty() && taggedVillager.isPresent() && taggedVillager.get().getVillagerData().profession().is(VillagerProfession.NONE)) {
            // No job site block recognized, and the nearest villager hasn't claimed a profession yet
            // -- re-checked periodically in case it (or whichever still-NONE villager ends up nearest)
            // claims a mapped profession soon, to upgrade this *plot's own Zone Type* (tagging is
            // handled separately, see above).
            NaturalVillageZoneRecheckPending.enqueueProfessionWatch(level, plotId, settlement, taggedVillager.get().getId());
        }
    }

    /**
     * Re-examines one {@link NaturalVillageZoneRecheckPending.Entry}, in whichever of its two modes
     * applies (see that class's own doc). Called periodically by {@code
     * NaturalVillagePlotGenerationTicker}. Removes the entry once it succeeds or its attempt budget
     * runs out.
     */
    static void processZoneRecheck(NaturalVillageZoneRecheckPending.Entry entry) {
        if (--entry.attemptsLeft <= 0) {
            NaturalVillageZoneRecheckPending.remove(entry.plotId);
        }
        if (entry.jobSitePos != null) {
            processJobSiteClaimRecheck(entry);
        } else {
            processProfessionWatchRecheck(entry);
        }
    }

    /** Job-site-claim mode: the plot's zone is already correct -- just waiting for a villager to tag as the real worker. */
    private static void processJobSiteClaimRecheck(NaturalVillageZoneRecheckPending.Entry entry) {
        Optional<Villager> claimant = findVillagerClaimingJobSite(entry.level, entry.jobSitePos);
        if (claimant.isEmpty()) {
            return; // Still unclaimed -- try again next pass.
        }
        claimant.get().getPersistentData().putIntArray(PLOT_ID_TAG, net.minecraft.core.UUIDUtil.uuidToIntArray(entry.plotId));
        SettlemyntsMod.LOGGER.info("Natural village plot: tagged the real job-site worker for plot {}", entry.plotId);
        NaturalVillageZoneRecheckPending.remove(entry.plotId);
    }

    /** Profession-watch mode: re-derives the zone from the originally-tagged villager's profession once it claims one, upgrading the plot in place if it's actually different. */
    private static void processProfessionWatchRecheck(NaturalVillageZoneRecheckPending.Entry entry) {
        ServerLevel level = entry.level;
        if (!(level.getEntity(entry.villagerEntityId) instanceof Villager villager) || villager.getClass() != Villager.class) {
            return; // Gone (died, wandered out of a loaded chunk) -- give up silently, same as attempt-budget exhaustion.
        }
        Optional<Identifier> byProfession = zoneTypeForProfession(villager.getVillagerData().profession());
        if (byProfession.isEmpty()) {
            return; // Still unclaimed, or claimed something this mod has no ZoneType mapping for -- try again next pass.
        }
        Optional<ZoneType> newZoneType = ZoneTypeRegistry.get(byProfession.get());
        if (newZoneType.isEmpty()) {
            return;
        }

        NaturalSettlementPlotOwner owner = new NaturalSettlementPlotOwner(level.getServer(), entry.settlement);
        PlotRecord plot = owner.getPlots().stream().filter(p -> p.plotId().equals(entry.plotId)).findFirst().orElse(null);
        if (plot == null || plot.zoneTypeId().equals(byProfession.get())) {
            NaturalVillageZoneRecheckPending.remove(entry.plotId); // Already matches (or plot is gone) -- nothing left to upgrade.
            return;
        }

        PlotRecord upgraded = new PlotRecord(plot.plotId(), newZoneType.get().label(), byProfession.get(),
                plot.cartographyrPlotEntityId(), plot.cartographyrBufferEntityId(), plot.boxPos(), plot.constructionBoxId(),
                plot.owner(), plot.billId(), plot.shopId(), plot.suppressedShopResources());
        owner.updatePlot(upgraded);
        Cartography.setDesignation(level, new EntityId(plot.cartographyrPlotEntityId()), newZoneType.get().label());
        if (ShopSeeding.hasCatalog(byProfession.get())) {
            NaturalShopVault.ensureVault(level, entry.settlement, byProfession.get(), villager.blockPosition());
            UUID sharedShopId = resolveOrCreateSharedShop(level, entry.settlement, byProfession.get(), upgraded.plotId());
            ShopSeeding.seedNewPlotSharingShop(level, upgraded, owner, villager.blockPosition(), sharedShopId);
        }
        SettlemyntsMod.LOGGER.info("Natural village plot: upgraded plot {} to {} after villager claimed a profession", plot.plotId(), byProfession.get());
        NaturalVillageZoneRecheckPending.remove(entry.plotId);
    }

    /**
     * Resolves the one Shop every plot of {@code zoneTypeId} in this settlement shares, minting a
     * fresh one (keyed off whichever plot asks first) if none exists yet.
     */
    private static UUID resolveOrCreateSharedShop(ServerLevel level, SettlementKey settlement, Identifier zoneTypeId, UUID fallbackPlotId) {
        NaturalSettlementPlotStore store = NaturalSettlementPlotStore.get(level.getServer());
        Optional<UUID> existing = store.getSharedShop(settlement, zoneTypeId);
        if (existing.isPresent()) {
            return existing.get();
        }
        UUID shopId = com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge.registerShop(level, fallbackPlotId);
        store.putSharedShop(settlement, zoneTypeId, shopId);
        return shopId;
    }

    /** Any vanilla Villager near the piece, regardless of profession -- used only when no real job-site block was found (see this method's own call site). */
    private static Optional<Villager> findNearbyVillager(ServerLevel level, BoundingBox box) {
        AABB search = new AABB(
                box.minX() - VILLAGER_SEARCH_PADDING_BLOCKS, box.minY() - VILLAGER_SEARCH_PADDING_BLOCKS, box.minZ() - VILLAGER_SEARCH_PADDING_BLOCKS,
                box.maxX() + VILLAGER_SEARCH_PADDING_BLOCKS, box.maxY() + VILLAGER_SEARCH_PADDING_BLOCKS, box.maxZ() + VILLAGER_SEARCH_PADDING_BLOCKS);
        return level.getEntitiesOfClass(Villager.class, search, v -> v.getClass() == Villager.class).stream().findFirst();
    }

    /** Any villager whose Brain has genuinely claimed {@code jobSitePos} as its own workstation (vanilla's own {@code MemoryModuleType.JOB_SITE}) -- see this class's own call site for why this replaced "nearest villager" for job-site-backed zones. Searched dimension-wide via a generous radius since a working villager often isn't standing right at its job site. */
    private static final double JOB_SITE_CLAIM_SEARCH_RADIUS_BLOCKS = 48.0;

    private static Optional<Villager> findVillagerClaimingJobSite(ServerLevel level, BlockPos jobSitePos) {
        AABB search = new AABB(jobSitePos).inflate(JOB_SITE_CLAIM_SEARCH_RADIUS_BLOCKS);
        return level.getEntitiesOfClass(Villager.class, search, v -> v.getClass() == Villager.class
                        && v.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE)
                        .map(globalPos -> globalPos.dimension().equals(level.dimension()) && globalPos.pos().equals(jobSitePos))
                        .orElse(false))
                .stream().findFirst();
    }

    private static Identifier inferZoneTypeWithoutJobSite(ServerLevel level, BoundingBox box, Optional<Villager> nearbyVillager) {
        if (nearbyVillager.isPresent()) {
            Optional<Identifier> byProfession = zoneTypeForProfession(nearbyVillager.get().getVillagerData().profession());
            if (byProfession.isPresent()) {
                return byProfession.get();
            }
        }
        if (isMostlyFarmland(level, box)) {
            return Identifier.fromNamespaceAndPath("blueprynts", "farm");
        }
        return Identifier.fromNamespaceAndPath("blueprynts", "private_residence");
    }

    private record JobSite(BlockPos pos, Identifier zoneTypeId) {
    }

    /**
     * Scans every block in (a padded region around) the piece for a vanilla job-site block this mod
     * maps to a real {@link ZoneType} -- the full mapping the user provided 2026-10-08: Composter ->
     * Farm (Farmer), Blast Furnace -> Armorer (its own Zone Type, distinct from Blacksmith), Smithing
     * Table/Grindstone -> Blacksmith (Toolsmith/Weaponsmith respectively), Smoker -> Restaurant
     * (Butcher), Barrel -> Grocer (Fisherman), Stonecutter -> Stonemason (Mason). Padded the same
     * {@link #VILLAGER_SEARCH_PADDING_BLOCKS} as the villager search -- a job-site block often sits
     * just outside the house's own footprint in its yard, sometimes even as its own separate (and
     * much smaller, filtered-out-as-"not a building") structure piece. Cartography Table/Cartographer,
     * Brewing Stand/Cleric, Fletching Table/Fletcher, Loom/Shepherd, and Lectern/Librarian are
     * deliberately still not checked -- the user left these "&lt;Unsure&gt;" in the same mapping,
     * no Zone Type decided yet. Leatherworker/Cauldron -> Tavern is also not checked -- the user
     * flagged Tavern itself as a future plot type, not yet registered anywhere. Checked ahead of
     * villager profession in the caller -- a job-site block is physically placed by world-gen and
     * tells you a building's purpose instantly, with none of profession-claiming's wait-and-hope
     * timing (real user expectation, 2026-10-08: "I see a building with an anvil [workstation block],
     * make that a blacksmith plot").
     */
    private static Optional<JobSite> findJobSite(ServerLevel level, BoundingBox box) {
        int minX = box.minX() - (int) VILLAGER_SEARCH_PADDING_BLOCKS;
        int maxX = box.maxX() + (int) VILLAGER_SEARCH_PADDING_BLOCKS;
        int minY = box.minY() - 2;
        int maxY = box.maxY() + 2;
        int minZ = box.minZ() - (int) VILLAGER_SEARCH_PADDING_BLOCKS;
        int maxZ = box.maxZ() + (int) VILLAGER_SEARCH_PADDING_BLOCKS;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    net.minecraft.world.level.block.Block block = level.getBlockState(pos).getBlock();
                    if (block == Blocks.COMPOSTER) {
                        return Optional.of(new JobSite(pos, Identifier.fromNamespaceAndPath("blueprynts", "farm")));
                    }
                    if (block == Blocks.BLAST_FURNACE) {
                        return Optional.of(new JobSite(pos, Identifier.fromNamespaceAndPath("blueprynts", "armorer")));
                    }
                    if (block == Blocks.SMITHING_TABLE || block == Blocks.GRINDSTONE) {
                        return Optional.of(new JobSite(pos, Identifier.fromNamespaceAndPath("blueprynts", "blacksmith")));
                    }
                    if (block == Blocks.SMOKER) {
                        return Optional.of(new JobSite(pos, Identifier.fromNamespaceAndPath("blueprynts", "restaurant")));
                    }
                    if (block == Blocks.BARREL) {
                        return Optional.of(new JobSite(pos, Identifier.fromNamespaceAndPath("blueprynts", "grocer")));
                    }
                    if (block == Blocks.STONECUTTER) {
                        return Optional.of(new JobSite(pos, Identifier.fromNamespaceAndPath("blueprynts", "stonemason")));
                    }
                }
            }
        }
        return Optional.empty();
    }

    // profession is a Holder<VillagerProfession> (a registry element), not a plain enum constant --
    // VillagerProfession.FARMER etc. are ResourceKeys, compared via Holder#is. Mapping per the user's
    // table, 2026-10-08 -- Cartographer/Cleric/Fletcher/Librarian/Shepherd left unmapped ("<Unsure>"
    // in that table); Leatherworker -> Tavern left unmapped (Tavern itself not yet a registered
    // ZoneType, flagged there as a future plot).
    static Optional<Identifier> zoneTypeForProfession(net.minecraft.core.Holder<VillagerProfession> profession) {
        if (profession.is(VillagerProfession.FARMER)) {
            return Optional.of(Identifier.fromNamespaceAndPath("blueprynts", "farm"));
        }
        if (profession.is(VillagerProfession.ARMORER)) {
            return Optional.of(Identifier.fromNamespaceAndPath("blueprynts", "armorer"));
        }
        if (profession.is(VillagerProfession.WEAPONSMITH) || profession.is(VillagerProfession.TOOLSMITH)) {
            return Optional.of(Identifier.fromNamespaceAndPath("blueprynts", "blacksmith"));
        }
        if (profession.is(VillagerProfession.BUTCHER)) {
            return Optional.of(Identifier.fromNamespaceAndPath("blueprynts", "restaurant"));
        }
        if (profession.is(VillagerProfession.FISHERMAN)) {
            return Optional.of(Identifier.fromNamespaceAndPath("blueprynts", "grocer"));
        }
        if (profession.is(VillagerProfession.MASON)) {
            return Optional.of(Identifier.fromNamespaceAndPath("blueprynts", "stonemason"));
        }
        return Optional.empty();
    }

    /**
     * The inverse of {@link #zoneTypeForProfession} -- one representative profession to force-assign
     * so a Zone Type's Shop always has at least one real worker, rather than waiting on vanilla's own
     * job-site-claiming AI (explicit user decision, 2026-10-08, after a 30+ minute live test across 4
     * towns found zero villagers had ever claimed a job on their own). Where a Zone Type maps from
     * more than one profession (Blacksmith: Weaponsmith/Toolsmith), picks one arbitrarily -- either
     * is equally valid for {@link NaturalVillageShopLinkTicker}'s own matching, which only cares that
     * {@code zoneTypeForProfession} of whatever gets assigned resolves back to this Zone Type.
     */
    public static Optional<net.minecraft.resources.ResourceKey<VillagerProfession>> representativeProfessionFor(Identifier zoneTypeId) {
        String path = zoneTypeId.getPath();
        if (!zoneTypeId.getNamespace().equals("blueprynts")) {
            return Optional.empty();
        }
        return switch (path) {
            case "farm" -> Optional.of(VillagerProfession.FARMER);
            case "armorer" -> Optional.of(VillagerProfession.ARMORER);
            case "blacksmith" -> Optional.of(VillagerProfession.TOOLSMITH);
            case "restaurant" -> Optional.of(VillagerProfession.BUTCHER);
            case "grocer" -> Optional.of(VillagerProfession.FISHERMAN);
            case "stonemason" -> Optional.of(VillagerProfession.MASON);
            default -> Optional.empty();
        };
    }

    private static boolean isMostlyFarmland(ServerLevel level, BoundingBox box) {
        int sampled = 0;
        int farmland = 0;
        // Per-column height (not one height sampled at the piece's center) -- a farm piece's
        // bounding box often also covers a well/fence/path at a different height than the tilled
        // interior, and a single center sample can easily land off the actual crop area entirely.
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                sampled++;
                int groundY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                if (level.getBlockState(new BlockPos(x, groundY, z)).is(Blocks.FARMLAND)) {
                    farmland++;
                }
            }
        }
        return sampled > 0 && (double) farmland / sampled >= FARMLAND_RATIO_THRESHOLD;
    }
}
