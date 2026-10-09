package com.github.cerealklla.settlemynts;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.github.cerealklla.cartographyr.api.Cartography;
import com.github.cerealklla.cartographyr.geo.Classification;
import com.github.cerealklla.cartographyr.geo.EntityDefinition;
import com.github.cerealklla.cartographyr.geo.EntityId;
import com.github.cerealklla.cartographyr.geo.EntityType;
import com.github.cerealklla.cartographyr.geo.GeographicEntity;
import com.github.cerealklla.cartographyr.geo.Geometry;
import com.github.cerealklla.cartographyr.geo.Layer;
import com.github.cerealklla.cartographyr.geo.LifecycleState;
import com.github.cerealklla.cartographyr.geo.PlotValidity;
import com.github.cerealklla.cartographyr.geo.ProtectionLevel;
import com.github.cerealklla.settlemynts.api.Settlemynts;
import com.github.cerealklla.settlemynts.bridge.BlueprintsConstructionBridge;
import com.github.cerealklla.settlemynts.bridge.ProtectyonsPlotBridge;
import com.github.cerealklla.settlemynts.bridge.YconomicsBillBridge;
import com.github.cerealklla.settlemynts.founding.ClientFoundingRequests;
import com.github.cerealklla.settlemynts.founding.FinalizeSettlementPayload;
import com.github.cerealklla.settlemynts.founding.GhostBoundaryWallEntity;
import com.github.cerealklla.settlemynts.founding.GhostPerimeterFencePostEntity;
import com.github.cerealklla.settlemynts.founding.GhostPerimeterStakeEntity;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.founding.GrantTownPlannerPayload;
import com.github.cerealklla.settlemynts.founding.OpenFoundingScreenPayload;
import com.github.cerealklla.settlemynts.founding.OpenStakeScreenPayload;
import com.github.cerealklla.settlemynts.founding.PerimeterFit;
import com.github.cerealklla.settlemynts.founding.RemoveStakePayload;
import com.github.cerealklla.settlemynts.founding.RequestPerimeterStakePayload;
import com.github.cerealklla.settlemynts.founding.SetBoundaryVisiblePayload;
import com.github.cerealklla.settlemynts.founding.SetSettlementNamePayload;
import com.github.cerealklla.settlemynts.founding.SetStakeAbsolutePayload;
import com.github.cerealklla.settlemynts.registration.ModBlockEntities;
import com.github.cerealklla.settlemynts.registration.ModBlocks;
import com.github.cerealklla.settlemynts.registration.ModEntities;
import com.github.cerealklla.settlemynts.registration.ModItems;
import com.github.cerealklla.settlemynts.rope.RopeFenceLeash;
import com.github.cerealklla.settlemynts.zone.FinalizePlotPayload;
import com.github.cerealklla.settlemynts.zone.GhostPlotFencePostEntity;
import com.github.cerealklla.settlemynts.zone.GhostPlotStakeEntity;
import com.github.cerealklla.settlemynts.zone.GhostPlotWallEntity;
import com.github.cerealklla.settlemynts.zone.OpenPlotStakeScreenPayload;
import com.github.cerealklla.settlemynts.zone.PlotGeometry;
import com.github.cerealklla.settlemynts.zone.PlotPermissions;
import com.github.cerealklla.settlemynts.zone.PlotRecord;
import com.github.cerealklla.settlemynts.zone.PlotSessionData;
import com.github.cerealklla.settlemynts.zone.GhostRoadAccessFlagEntity;
import com.github.cerealklla.settlemynts.zone.GhostRoadAccessPreviewEntity;
import com.github.cerealklla.settlemynts.zone.RemovePlotStakePayload;
import com.github.cerealklla.settlemynts.zone.RequestPlotStakePayload;
import com.github.cerealklla.settlemynts.zone.RequestRoadAccessFlagPayload;
import com.github.cerealklla.settlemynts.zone.SetShowPlotPerimetersPayload;
import com.github.cerealklla.settlemynts.zone.ZoneType;
import com.github.cerealklla.settlemynts.zone.ZoneTypeRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

// The value here must match the modId entry in META-INF/neoforge.mods.toml (sourced from mod_id in gradle.properties)
@Mod(SettlemyntsMod.MODID)
public class SettlemyntsMod {
    public static final String MODID = "settlemynts";
    public static final Logger LOGGER = LogUtils.getLogger();

    // Design doc Section 11a: plots live on their own "zone" layer, separate from Cartographyr's
    // built-in Region/Settlement layers -- placement -1, below both (0/1), matching "more specific
    // things stack below more general ones" in Lyfe's HUD ordering convention.
    public static final Identifier ZONE_LAYER_ID = Identifier.fromNamespaceAndPath(MODID, "zone");

    // Open, trust-based (same governance as Cartographyr's own EntityType) -- Settlemynts owns
    // these two since it's the mod actually creating plot entities; a future Blueprynts (or any
    // other mod) can define its own EntityType for whatever it registers, no coordination needed.
    public static final EntityType PLOT_ENTITY_TYPE = new EntityType(Identifier.fromNamespaceAndPath(MODID, "plot"));
    public static final EntityType PLOT_BUFFER_ENTITY_TYPE = new EntityType(Identifier.fromNamespaceAndPath(MODID, "plot_buffer"));
    // Deliberately a different EntityType from PLOT_ENTITY_TYPE (added 2026-10-08) -- a natural
    // village's auto-generated per-building plots are explicitly NOT meant to render on the minimap
    // (real user feedback during testing: the per-building outlines were visual clutter players don't
    // need to see), unlike a player-founded plot. Lyfe's MinimapTracker only allow-lists
    // PLOT_ENTITY_TYPE, so this type is invisible there for free -- everything else about a natural
    // plot (ZoneType, Shop, etc.) is otherwise identical to a real plot; see zone.NaturalVillagePlotGenerator.
    public static final EntityType NATURAL_PLOT_ENTITY_TYPE = new EntityType(Identifier.fromNamespaceAndPath(MODID, "natural_plot"));

    // The settlement's *real* (unpadded) fitted polygon, registered alongside the existing
    // Classification.CONSTRUCTED/EntityType.SETTLEMENT entity (which keeps its padded geometry
    // unchanged, so SettlementFounding's distance check is completely unaffected). Added 2026-09-26
    // so Lyfe's HUD can tell "genuinely inside the built town" (this entity, resolves to
    // "Outskirts" unless also in a plot/plot-buffer) apart from "inside the settlement's own ~10-
    // block outer padding buffer only" (the existing padded entity, resolves to "No Man's Land") --
    // see Lyfe's LocationTracker for the corrected 2026-09-27 label mapping. Purely additive;
    // nothing that already reads EntityType.SETTLEMENT needs to change.
    public static final EntityType SETTLEMENT_CORE_ENTITY_TYPE = new EntityType(Identifier.fromNamespaceAndPath(MODID, "settlement_core"));

    // Design doc Section 11a: "Town Proper" buffer padding, added outward from each plot's own
    // centroid -- deliberately smaller than the settlement's own CARTOGRAPHYR_PADDING_BLOCKS (10),
    // since a plot's buffer is meant to cover just the narrow gap between adjacent plots, not a
    // whole "no man's zone" the way a settlement's perimeter buffer is. History: 3.0 -> 1.0
    // (2026-09-26 playtest feedback) -> 3.0 (2026-09-27, after seeing 1 block in practice) -> 6.0
    // (2026-09-27, later same day, explicit user request). Radial padding from the centroid scales
    // more at sharper polygon corners than at shallow ones (an inherent property of this technique,
    // not a bug -- see PlotGeometry's own doc), so a corner can occasionally land further out than
    // this exact value; accepted as-is rather than chasing an adaptive per-vertex padding scheme.
    public static final double PLOT_BUFFER_PADDING_BLOCKS = 6.0;

    public SettlemyntsMod(IEventBus modEventBus, ModContainer modContainer) {
        ModItems.ITEMS.register(modEventBus);
        ModItems.DATA_COMPONENTS.register(modEventBus);
        ModEntities.ENTITIES.register(modEventBus);
        ModBlocks.BLOCKS.register(modEventBus);
        ModBlocks.ITEMS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        com.github.cerealklla.settlemynts.registration.ModMenus.MENU_TYPES.register(modEventBus);

        Cartography.registerLayer(new Layer(ZONE_LAYER_ID, "Zone", -1));
        // 2026-10-06 fix (real report: a creeper destroyed part of a building) -- this layer never
        // had a default ProtectionLevel set, so every Plot/Plot-buffer entity (both registered under
        // ZONE_LAYER_ID with no explicit protectionLevel -- see finalizePlot) silently resolved to
        // ProtectionLevel.UNPROTECTED on its own (GeographicEntity#create's fallback chain), only
        // ever covered incidentally when a plot happened to also sit inside the broader settlement's
        // own protected polygon. Matches Layer.SETTLEMENT_ID's own default -- a plot is never less
        // protected than the settlement around it.
        Cartography.setDefaultProtectionLevel(ZONE_LAYER_ID, ProtectionLevel.NO_VOXEL_CHANGE_ALONG_SURFACE_AND_UP);

        // Built-in zone type (design doc Section 11a) -- Settlemynts only ships this one natively;
        // Blueprynts (and others) are expected to register the rest via Settlemynts.registerZoneType.
        // Town Hall stays native/Settlemynts-only on purpose -- it's tied directly to the Ghost Town
        // Hall Core mechanic, not a generic buildable type a player picks a Blueprint for.
        //
        // "Private Residence" used to also be registered natively here, under
        // settlemynts:private_residence -- removed 2026-10-01 per explicit user request: it's now
        // purely the Blueprynts-bridged blueprynts:private_residence BlueprintType (see
        // BluepryntsMod#commonSetup / bridge.SettlemyntsZoneBridge there), the same "a plot of this
        // type picks a real Blueprint" treatment Farm/Lumberyard/Blacksmith/Guardhouse already get.
        // Having both registered at once (same label, different Identifier) meant a Planner could
        // pick the native one and never see any of the Blueprints actually saved under the Blueprynts
        // type -- that duplicate is exactly what this removal fixes. Like those other four, "Private
        // Residence" is now simply absent from the zone-type list entirely on a Blueprynts-less
        // server, rather than falling back to a non-Blueprint-backed native version.
        // npcOwnedOnly=true (2026-10-05, real report: "Town hall should also be an NPC own building
        // only... Mayor being allowed full access as they do for every plot, just like Guardhouse") --
        // Town Hall is civic infrastructure, never a player-assignable Owner; Mayor+Town Planner
        // access already works for every plot regardless of owner (PlotPermissions), so no further
        // change was needed beyond this flag once hasShop/resident-spawning were already excluded.
        Settlemynts.registerZoneType(new ZoneType(
                Identifier.fromNamespaceAndPath(MODID, "town_hall"), "Town Hall", Blocks.WHITE_STAINED_GLASS, true));

        NeoForge.EVENT_BUS.register(this);
        NeoForge.EVENT_BUS.register(new com.github.cerealklla.settlemynts.plotsign.PlotConfigSignLocatorTicker());
        NeoForge.EVENT_BUS.register(new com.github.cerealklla.settlemynts.plotsign.LocatorCancelListener());
        NeoForge.EVENT_BUS.register(new com.github.cerealklla.settlemynts.construction.NpcAutoFundingTicker());
        NeoForge.EVENT_BUS.register(new com.github.cerealklla.settlemynts.bills.PlotRentTicker());
        NeoForge.EVENT_BUS.register(new com.github.cerealklla.settlemynts.guardhouse.GuardSpawnTicker());
        NeoForge.EVENT_BUS.register(new com.github.cerealklla.settlemynts.plotsign.PlotShopProximityTicker());
        NeoForge.EVENT_BUS.register(new com.github.cerealklla.settlemynts.resident.ResidentSpawnTicker());
        NeoForge.EVENT_BUS.register(new com.github.cerealklla.settlemynts.resident.ResidentConversionGuard());
        NeoForge.EVENT_BUS.register(new com.github.cerealklla.settlemynts.zone.ShopMidnightRestockTicker());
        NeoForge.EVENT_BUS.register(new com.github.cerealklla.settlemynts.zone.PlotCraftingTicker());
        NeoForge.EVENT_BUS.register(new com.github.cerealklla.settlemynts.lumberyard.LumberjackSpawnTicker());
        NeoForge.EVENT_BUS.register(new com.github.cerealklla.settlemynts.farm.FarmerSpawnTicker());
        NeoForge.EVENT_BUS.register(new com.github.cerealklla.settlemynts.founding.TownHallCoreLocatorTicker());
        NeoForge.EVENT_BUS.register(new com.github.cerealklla.settlemynts.founding.LocatorCancelListener());
        NeoForge.EVENT_BUS.register(new com.github.cerealklla.settlemynts.zone.PlotStakeTossGuard());
        NeoForge.EVENT_BUS.register(new com.github.cerealklla.settlemynts.roadway.RoadwayTierTicker());
        NeoForge.EVENT_BUS.register(new com.github.cerealklla.settlemynts.roadway.RoadwayClearanceListener());
        NeoForge.EVENT_BUS.register(new com.github.cerealklla.settlemynts.zone.NaturalVillagePlotListener());
        NeoForge.EVENT_BUS.register(new com.github.cerealklla.settlemynts.zone.NaturalVillagePlotGenerationTicker());
        NeoForge.EVENT_BUS.register(new com.github.cerealklla.settlemynts.zone.NaturalVillageShopLinkTicker());
        NeoForge.EVENT_BUS.register(new com.github.cerealklla.settlemynts.zone.NaturalShopVaultProtectionListener());
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStartingEvent event) ->
                com.github.cerealklla.settlemynts.zone.NaturalVillagePlotPending.clearStaleQueuesOnServerStart());
        NeoForge.EVENT_BUS.register(new com.github.cerealklla.settlemynts.resident.VillagerDeathListener());
        NeoForge.EVENT_BUS.register(new com.github.cerealklla.settlemynts.resident.VillagerRespawnTicker());
        NeoForge.EVENT_BUS.register(new com.github.cerealklla.settlemynts.resident.VillagerShopInteractListener());
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.RegisterCommandsEvent event) ->
                com.github.cerealklla.settlemynts.debug.DebugCommands.register(event.getDispatcher()));

        // Replaces the old unconditional-on-login debug item grant (removed 2026-10-02, see
        // decisions.md) -- the Settlement Claim Flag is now only ever handed out via Kyt's
        // mod-managed "Dev" Kyt (/kyt getDev). Same soft-dependency gate as KytGuardhouseBridge's
        // own usage below.
        if (ModList.get().isLoaded("kyt")) {
            com.github.cerealklla.kyt.api.Kyt.registerDevKytContribution(() ->
                    java.util.List.of(new ItemStack(ModItems.SETTLEMENT_CLAIM_FLAG.get(), 2)));
        }

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::registerPayloads);
        modEventBus.addListener(this::registerAttributes);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Settlemynts common setup");
    }

    /** {@code GuardEntity} is this mod's first real {@code Mob} -- every other registered entity is a non-physical ghost marker needing no attributes at all. */
    private void registerAttributes(net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent event) {
        event.put(ModEntities.GUARD.get(), com.github.cerealklla.settlemynts.guardhouse.GuardEntity.createAttributes().build());
        event.put(ModEntities.RESIDENT_VILLAGER.get(), com.github.cerealklla.settlemynts.resident.ResidentVillagerEntity.createAttributes().build());
        event.put(ModEntities.LUMBERJACK_WORKER.get(), com.github.cerealklla.settlemynts.lumberyard.LumberjackWorkerEntity.createAttributes().build());
        event.put(ModEntities.FARMER_WORKER.get(), com.github.cerealklla.settlemynts.farm.FarmerWorkerEntity.createAttributes().build());
    }

    /** Payload registrations for the staking mechanic (design doc Section 6) -- see founding.* payload classes' own docs. */
    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");

        // Server-to-client: hand off to the client-only bridge, never touch Screen/Minecraft here
        // (this method runs on both sides -- see ClientFoundingRequests' own doc).
        registrar.playToClient(OpenFoundingScreenPayload.TYPE, OpenFoundingScreenPayload.STREAM_CODEC,
                (payload, context) -> ClientFoundingRequests.requestFoundingScreen(payload));
        registrar.playToClient(OpenStakeScreenPayload.TYPE, OpenStakeScreenPayload.STREAM_CODEC,
                (payload, context) -> ClientFoundingRequests.requestStakeScreen(payload));

        registrar.playToServer(SetSettlementNamePayload.TYPE, SetSettlementNamePayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player
                            && player.level().getEntity(payload.coreEntityId()) instanceof GhostTownHallCoreEntity core
                            && core.isTownPlanner(player.getUUID())) {
                        core.setSettlementName(payload.name());
                    }
                });

        registrar.playToServer(GrantTownPlannerPayload.TYPE, GrantTownPlannerPayload.STREAM_CODEC,
                (payload, context) -> {
                    if (!(context.player() instanceof ServerPlayer player)
                            || !(player.level().getEntity(payload.coreEntityId()) instanceof GhostTownHallCoreEntity core)) {
                        return;
                    }
                    ServerPlayer target = player.level().getServer().getPlayerList().getPlayerByName(payload.targetPlayerName());
                    if (target == null) {
                        player.sendSystemMessage(Component.literal(
                                "No online player named \"" + payload.targetPlayerName() + "\" (granting an offline player isn't supported yet)."));
                        return;
                    }
                    if (!core.grantTownPlanner(player.getUUID(), target.getUUID())) {
                        player.sendSystemMessage(Component.literal(
                                "Only the settlement's founder can grant Town Planner permission."));
                        return;
                    }
                    // Keep every already-finalized plot's Protectyons permission set in sync with the
                    // settlement's own Town Planner roster, 2026-09-29 -- see ProtectyonsPlotBridge's
                    // own doc for why this is a separate registration from Cartographyr's. Recomputed
                    // per-plot via PlotPermissions (reworked 2026-10-05) rather than just passing
                    // core.getTownPlanners() -- a privately-owned plot's permitted set must stay
                    // Mayor+Owner only, not gain every Town Planner just because the roster changed.
                    if (player.level() instanceof ServerLevel serverLevel && ModList.get().isLoaded("protectyons")) {
                        for (PlotRecord plot : core.getPlots()) {
                            ProtectyonsPlotBridge.updatePermittedPlayers(serverLevel, plot.plotId(),
                                    PlotPermissions.computeProtectionPermittedPlayers(plot, core));
                        }
                        // Settlement-wide area (2026-10-05) -- unlike a plot's permitted set, this one
                        // IS just the full Town Planner roster, no per-plot Owner-only narrowing.
                        // Only registered once Finalize has run (registerSettlement); updatePermittedPlayers
                        // is a safe no-op against an unregistered areaId otherwise.
                        if (core.isFinalized()) {
                            ProtectyonsPlotBridge.updateSettlementPermittedPlayers(serverLevel, core.getUUID(), core.getTownPlanners());
                        }
                    }
                });

        registrar.playToServer(RequestPerimeterStakePayload.TYPE, RequestPerimeterStakePayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player
                            && player.level().getEntity(payload.coreEntityId()) instanceof GhostTownHallCoreEntity core
                            && core.isTownPlanner(player.getUUID())) {
                        ItemStack stake = new ItemStack(ModItems.PLANNED_PERIMETER_STAKE.get());
                        if (!player.getInventory().add(stake)) {
                            player.drop(stake, false);
                        }
                    }
                });

        registrar.playToServer(SetStakeAbsolutePayload.TYPE, SetStakeAbsolutePayload.STREAM_CODEC,
                (payload, context) -> {
                    if (!(context.player() instanceof ServerPlayer player)
                            || !(player.level() instanceof ServerLevel serverLevel)
                            || !(serverLevel.getEntity(payload.stakeEntityId()) instanceof GhostPerimeterStakeEntity stake)) {
                        return;
                    }
                    GhostTownHallCoreEntity core = ownerCoreOf(serverLevel, stake);
                    if (core == null || !core.isTownPlanner(player.getUUID())) {
                        return;
                    }
                    if (payload.absolute()) {
                        long absoluteCount = GhostPerimeterStakeEntity.findByOwnerCore(serverLevel, stake.getOwnerCoreId()).stream()
                                .filter(GhostPerimeterStakeEntity::isAbsolute).count();
                        if (absoluteCount >= GhostPerimeterStakeEntity.MAX_ABSOLUTE_STAKES) {
                            player.sendSystemMessage(Component.literal(
                                    "Already at the maximum of " + GhostPerimeterStakeEntity.MAX_ABSOLUTE_STAKES + " absolute stakes."));
                            return;
                        }
                    }
                    stake.setAbsolute(payload.absolute());
                });

        registrar.playToServer(RemoveStakePayload.TYPE, RemoveStakePayload.STREAM_CODEC,
                (payload, context) -> {
                    if (!(context.player() instanceof ServerPlayer player)
                            || !(player.level() instanceof ServerLevel serverLevel)
                            || !(serverLevel.getEntity(payload.stakeEntityId()) instanceof GhostPerimeterStakeEntity stake)) {
                        return;
                    }
                    GhostTownHallCoreEntity core = ownerCoreOf(serverLevel, stake);
                    if (core == null || !core.isTownPlanner(player.getUUID())) {
                        return;
                    }
                    UUID activePlanner = core.getActivePerimeterPlanner();
                    if (activePlanner != null && !activePlanner.equals(player.getUUID())) {
                        player.sendSystemMessage(Component.literal("Someone else is already placing this settlement's perimeter stakes."));
                        return;
                    }
                    // Right-clicking ANY stake removes the actual most-recently-placed one, regardless
                    // of which one was targeted (2026-09-27, explicit user request -- undo-style, not a
                    // rejection) -- keeps the placement-index scheme gap-free the same way blocking did.
                    List<GhostPerimeterStakeEntity> siblings = GhostPerimeterStakeEntity.findByOwnerCore(serverLevel, core.getUUID());
                    siblings.stream().max(Comparator.comparingInt(GhostPerimeterStakeEntity::getPlacementIndex))
                            .ifPresent(lastStake -> lastStake.remove(player));
                    if (siblings.size() <= 1) {
                        core.setActivePerimeterPlanner(null); // Back down to zero -- nothing in progress anymore.
                    }
                    GhostPerimeterFencePostEntity.regenerate(serverLevel, core);
                });

        registrar.playToServer(FinalizeSettlementPayload.TYPE, FinalizeSettlementPayload.STREAM_CODEC,
                (payload, context) -> finalizeSettlement(payload, context));

        registrar.playToServer(SetBoundaryVisiblePayload.TYPE, SetBoundaryVisiblePayload.STREAM_CODEC,
                (payload, context) -> setBoundaryVisible(payload, context));

        // Plot subdivision (design doc Section 11a).
        registrar.playToClient(OpenPlotStakeScreenPayload.TYPE, OpenPlotStakeScreenPayload.STREAM_CODEC,
                (payload, context) -> ClientFoundingRequests.requestPlotStakeScreen(payload));

        registrar.playToServer(RequestPlotStakePayload.TYPE, RequestPlotStakePayload.STREAM_CODEC,
                (payload, context) -> requestPlotStake(payload, context));

        registrar.playToServer(RemovePlotStakePayload.TYPE, RemovePlotStakePayload.STREAM_CODEC,
                (payload, context) -> {
                    if (!(context.player() instanceof ServerPlayer player)
                            || !(player.level() instanceof ServerLevel serverLevel)
                            || !(serverLevel.getEntity(payload.stakeEntityId()) instanceof GhostPlotStakeEntity stake)) {
                        return;
                    }
                    if (!(serverLevel.getEntity(stake.getOwnerCoreId()) instanceof GhostTownHallCoreEntity core)
                            || !core.isTownPlanner(player.getUUID())) {
                        return;
                    }
                    // Right-clicking ANY stake removes the actual most-recently-placed one, regardless
                    // of which one was targeted (2026-09-27, explicit user request -- undo-style, not a
                    // rejection) -- keeps the placement-index scheme gap-free the same way blocking did.
                    UUID ownerCoreId = stake.getOwnerCoreId();
                    UUID plotSessionId = stake.getPlotSessionId();
                    List<GhostPlotStakeEntity> siblings = GhostPlotStakeEntity.findBySession(serverLevel, ownerCoreId, plotSessionId);
                    siblings.stream().max(Comparator.comparingInt(GhostPlotStakeEntity::getPlacementIndex))
                            .ifPresent(lastStake -> {
                                // Rope Fence rework: this stake may hold a rope to a sibling -- clear
                                // that sibling's own reciprocal link so its slot re-opens, before discarding.
                                for (UUID linkedId : lastStake.getLinkIds()) {
                                    siblings.stream().filter(s -> s.getUUID().equals(linkedId)).findFirst()
                                            .ifPresent(other -> other.clearLink(lastStake.getUUID()));
                                }
                                // The live carry-preview marker (if any) is leashed TO this stake -- if it's
                                // left alive, its next tick finds its holder gone and vanilla auto-drops a
                                // Lead item (Leashable#tickLeash -> dropLeash). Discard it first so that
                                // never fires; RopeFenceLeash.clearGhostAnchor below only clears the
                                // tracking map, it doesn't touch the marker entity itself.
                                GhostPlotFencePostEntity.regenerateCarryPreview(serverLevel, ownerCoreId, plotSessionId, null, null);
                                lastStake.remove(player);
                            });
                    RopeFenceLeash.clearGhostAnchor(player.getUUID());
                });

        registrar.playToServer(FinalizePlotPayload.TYPE, FinalizePlotPayload.STREAM_CODEC,
                (payload, context) -> finalizePlot(payload, context));

        registrar.playToServer(SetShowPlotPerimetersPayload.TYPE, SetShowPlotPerimetersPayload.STREAM_CODEC,
                (payload, context) -> setShowPlotPerimeters(payload, context));

        // Roadways Milestone 1 (added 2026-10-06).
        registrar.playToServer(com.github.cerealklla.settlemynts.roadway.RequestRoadwayStakePayload.TYPE,
                com.github.cerealklla.settlemynts.roadway.RequestRoadwayStakePayload.STREAM_CODEC,
                (payload, context) -> requestRoadwayStake(payload, context));

        registrar.playToServer(com.github.cerealklla.settlemynts.roadway.SetShowRoadwayStakesPayload.TYPE,
                com.github.cerealklla.settlemynts.roadway.SetShowRoadwayStakesPayload.STREAM_CODEC,
                (payload, context) -> setShowRoadwayStakes(payload, context));

        registrar.playToServer(com.github.cerealklla.settlemynts.founding.RepositionTownHallCorePayload.TYPE,
                com.github.cerealklla.settlemynts.founding.RepositionTownHallCorePayload.STREAM_CODEC,
                (payload, context) -> repositionTownHallCore(payload, context));

        registrar.playToServer(RequestRoadAccessFlagPayload.TYPE, RequestRoadAccessFlagPayload.STREAM_CODEC,
                (payload, context) -> {
                    if (!(context.player() instanceof ServerPlayer player)
                            || !(player.level() instanceof ServerLevel serverLevel)
                            || !(serverLevel.getEntity(payload.stakeEntityId()) instanceof GhostPlotStakeEntity stake)) {
                        return;
                    }
                    if (!(serverLevel.getEntity(stake.getOwnerCoreId()) instanceof GhostTownHallCoreEntity core)
                            || !core.isTownPlanner(player.getUUID())) {
                        return;
                    }
                    ItemStack flag = new ItemStack(ModItems.ROAD_ACCESS_FLAG.get());
                    flag.set(ModItems.PLOT_SESSION_DATA, new PlotSessionData(stake.getOwnerCoreId(), stake.getPlotSessionId()));
                    if (!player.getInventory().add(flag)) {
                        player.drop(flag, false);
                    }
                });

        // Plot Config Sign (design doc Section 14a).
        registrar.playToClient(com.github.cerealklla.settlemynts.plotsign.OpenPlotConfigSignMenuPayload.TYPE,
                com.github.cerealklla.settlemynts.plotsign.OpenPlotConfigSignMenuPayload.STREAM_CODEC,
                (payload, context) -> com.github.cerealklla.settlemynts.plotsign.ClientPlotSignRequests.requestMenu(payload));

        registrar.playToServer(com.github.cerealklla.settlemynts.plotsign.RelocatePlotSignPayload.TYPE,
                com.github.cerealklla.settlemynts.plotsign.RelocatePlotSignPayload.STREAM_CODEC,
                (payload, context) -> relocatePlotSign(payload, context));

        // Real Shop system (2026-10-05, replacing the old inert placeholder) -- see
        // bridge.YconomicsShopBridge and api.Settlemynts' Shop methods' own docs.
        registrar.playToServer(com.github.cerealklla.settlemynts.plotsign.RequestShopPayload.TYPE,
                com.github.cerealklla.settlemynts.plotsign.RequestShopPayload.STREAM_CODEC,
                (payload, context) -> requestShop(payload, context));

        registrar.playToClient(com.github.cerealklla.settlemynts.plotsign.OpenShopPayload.TYPE,
                com.github.cerealklla.settlemynts.plotsign.OpenShopPayload.STREAM_CODEC,
                (payload, context) -> com.github.cerealklla.settlemynts.plotsign.ClientPlotSignRequests.requestShop(payload));

        registrar.playToServer(com.github.cerealklla.settlemynts.plotsign.BuyFromShopPayload.TYPE,
                com.github.cerealklla.settlemynts.plotsign.BuyFromShopPayload.STREAM_CODEC,
                (payload, context) -> buyFromShop(payload, context));

        registrar.playToServer(com.github.cerealklla.settlemynts.plotsign.SellToShopPayload.TYPE,
                com.github.cerealklla.settlemynts.plotsign.SellToShopPayload.STREAM_CODEC,
                (payload, context) -> sellToShop(payload, context));

        registrar.playToServer(com.github.cerealklla.settlemynts.plotsign.SetShopListingsPayload.TYPE,
                com.github.cerealklla.settlemynts.plotsign.SetShopListingsPayload.STREAM_CODEC,
                (payload, context) -> setShopListings(payload, context));

        // Planned Inventory (2026-10-09) -- see zone.PlannedInventoryClearing's own doc.
        registrar.playToServer(com.github.cerealklla.settlemynts.plotsign.RequestPlannedInventoryPayload.TYPE,
                com.github.cerealklla.settlemynts.plotsign.RequestPlannedInventoryPayload.STREAM_CODEC,
                (payload, context) -> requestPlannedInventory(payload, context));

        registrar.playToClient(com.github.cerealklla.settlemynts.plotsign.OpenPlannedInventoryPayload.TYPE,
                com.github.cerealklla.settlemynts.plotsign.OpenPlannedInventoryPayload.STREAM_CODEC,
                (payload, context) -> com.github.cerealklla.settlemynts.plotsign.ClientPlotSignRequests.requestPlannedInventory(payload));

        registrar.playToServer(com.github.cerealklla.settlemynts.plotsign.SetPlannedInventoryPayload.TYPE,
                com.github.cerealklla.settlemynts.plotsign.SetPlannedInventoryPayload.STREAM_CODEC,
                (payload, context) -> setPlannedInventory(payload, context));

        // "Press G to open shop" proximity prompt (2026-10-05) -- see PlotShopProximityTicker's own doc.
        registrar.playToClient(com.github.cerealklla.settlemynts.plotsign.PlotShopPromptPayload.TYPE,
                com.github.cerealklla.settlemynts.plotsign.PlotShopPromptPayload.STREAM_CODEC,
                (payload, context) -> com.github.cerealklla.settlemynts.plotsign.client.ClientShopPromptState.set(payload.present(), payload.anchor()));

        // "Distance from Stake" readout for Plot Placement Stakes/Roadway Stakes (2026-10-09).
        registrar.playToClient(com.github.cerealklla.settlemynts.founding.AnchorDistancePayload.TYPE,
                com.github.cerealklla.settlemynts.founding.AnchorDistancePayload.STREAM_CODEC,
                (payload, context) -> com.github.cerealklla.settlemynts.founding.client.ClientAnchorDistanceState.set(payload.present(), payload.anchorPos()));

        // "Plot Details" (any plot's own sign) / "Plot Management" (Town Hall sign only) -- 2026-10-05.
        registrar.playToServer(com.github.cerealklla.settlemynts.plotsign.RequestPlotDetailsPayload.TYPE,
                com.github.cerealklla.settlemynts.plotsign.RequestPlotDetailsPayload.STREAM_CODEC,
                (payload, context) -> requestPlotDetails(payload, context));

        registrar.playToClient(com.github.cerealklla.settlemynts.plotsign.OpenPlotDetailsPayload.TYPE,
                com.github.cerealklla.settlemynts.plotsign.OpenPlotDetailsPayload.STREAM_CODEC,
                (payload, context) -> com.github.cerealklla.settlemynts.plotsign.ClientPlotSignRequests.requestPlotDetails(payload));

        registrar.playToServer(com.github.cerealklla.settlemynts.plotsign.RequestPlotManagementPayload.TYPE,
                com.github.cerealklla.settlemynts.plotsign.RequestPlotManagementPayload.STREAM_CODEC,
                (payload, context) -> requestPlotManagement(payload, context));

        registrar.playToServer(com.github.cerealklla.settlemynts.plotsign.RequestUpgradePlotPayload.TYPE,
                com.github.cerealklla.settlemynts.plotsign.RequestUpgradePlotPayload.STREAM_CODEC,
                (payload, context) -> requestUpgradePlot(payload, context));

        registrar.playToServer(com.github.cerealklla.settlemynts.plotsign.RequestUpgradePlotPreviewPayload.TYPE,
                com.github.cerealklla.settlemynts.plotsign.RequestUpgradePlotPreviewPayload.STREAM_CODEC,
                (payload, context) -> requestUpgradePlotPreview(payload, context));

        registrar.playToClient(com.github.cerealklla.settlemynts.plotsign.UpgradePlotPreviewPayload.TYPE,
                com.github.cerealklla.settlemynts.plotsign.UpgradePlotPreviewPayload.STREAM_CODEC,
                (payload, context) -> com.github.cerealklla.settlemynts.plotsign.ClientPlotSignRequests.requestUpgradePlotPreview(payload));

        registrar.playToClient(com.github.cerealklla.settlemynts.plotsign.OpenPlotManagementPayload.TYPE,
                com.github.cerealklla.settlemynts.plotsign.OpenPlotManagementPayload.STREAM_CODEC,
                (payload, context) -> com.github.cerealklla.settlemynts.plotsign.ClientPlotSignRequests.requestPlotManagement(payload));

        // Guardhouse Plot Type (design doc Section 14a) -- "Configure Garrison."
        registrar.playToServer(com.github.cerealklla.settlemynts.guardhouse.RequestConfigureGarrisonPayload.TYPE,
                com.github.cerealklla.settlemynts.guardhouse.RequestConfigureGarrisonPayload.STREAM_CODEC,
                (payload, context) -> requestConfigureGarrison(payload, context));

        registrar.playToClient(com.github.cerealklla.settlemynts.guardhouse.OpenConfigureGarrisonPayload.TYPE,
                com.github.cerealklla.settlemynts.guardhouse.OpenConfigureGarrisonPayload.STREAM_CODEC,
                (payload, context) -> com.github.cerealklla.settlemynts.guardhouse.ClientGuardhouseRequests.requestMenu(payload));

        registrar.playToServer(com.github.cerealklla.settlemynts.guardhouse.SetGarrisonSlotPayload.TYPE,
                com.github.cerealklla.settlemynts.guardhouse.SetGarrisonSlotPayload.STREAM_CODEC,
                (payload, context) -> setGarrisonSlot(payload, context));

        // "Select Kyt" (optional Kyt soft dependency -- see bridge.KytGuardhouseBridge).
        registrar.playToServer(com.github.cerealklla.settlemynts.guardhouse.RequestGarrisonKytNamesPayload.TYPE,
                com.github.cerealklla.settlemynts.guardhouse.RequestGarrisonKytNamesPayload.STREAM_CODEC,
                (payload, context) -> requestGarrisonKytNames(payload, context));

        registrar.playToClient(com.github.cerealklla.settlemynts.guardhouse.OpenGarrisonKytPickerPayload.TYPE,
                com.github.cerealklla.settlemynts.guardhouse.OpenGarrisonKytPickerPayload.STREAM_CODEC,
                (payload, context) -> com.github.cerealklla.settlemynts.guardhouse.ClientGuardhouseRequests.requestKytPicker(payload));

        registrar.playToServer(com.github.cerealklla.settlemynts.guardhouse.SetGarrisonKytPayload.TYPE,
                com.github.cerealklla.settlemynts.guardhouse.SetGarrisonKytPayload.STREAM_CODEC,
                (payload, context) -> setGarrisonKyt(payload, context));
    }

    /** "Select Kyt" button on {@code client.ConfigureGarrisonScreen} -- re-checks {@code zone.PlotPermissions#canManage} the same way {@link #setGarrisonSlot} does, then replies with the server's real Kyt name list (see {@code bridge.KytGuardhouseBridge}). A Kyt-less server never reaches this handler at all in practice (the screen's own "Select Kyt" row is hidden client-side via {@code OpenConfigureGarrisonPayload#kytAvailable}), but this still guards {@link com.github.cerealklla.settlemynts.bridge.KytGuardhouseBridge#isLoaded} itself before touching the bridge, same isolation convention as every other optional dependency here. */
    private static void requestGarrisonKytNames(com.github.cerealklla.settlemynts.guardhouse.RequestGarrisonKytNamesPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!canManageGuardhouseSlot(serverLevel, player, payload.guardhousePos(), payload.slotIndex())) {
            return;
        }
        if (!com.github.cerealklla.settlemynts.bridge.KytGuardhouseBridge.isLoaded()) {
            return;
        }
        PacketDistributor.sendToPlayer(player, new com.github.cerealklla.settlemynts.guardhouse.OpenGarrisonKytPickerPayload(
                payload.guardhousePos(), payload.slotIndex(), com.github.cerealklla.settlemynts.bridge.KytGuardhouseBridge.listKytNames()));
    }

    /** "Select"/"Clear" on {@code client.SelectKytScreen} -- re-checks permission the same way, never trusts the client's own name. */
    private static void setGarrisonKyt(com.github.cerealklla.settlemynts.guardhouse.SetGarrisonKytPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!canManageGuardhouseSlot(serverLevel, player, payload.guardhousePos(), payload.slotIndex())) {
            return;
        }
        if (!(serverLevel.getBlockEntity(payload.guardhousePos()) instanceof com.github.cerealklla.settlemynts.guardhouse.GuardhouseBlockEntity guardhouse)) {
            return;
        }
        guardhouse.setGarrisonKyt(payload.slotIndex(), payload.kytName());
    }

    /** Shared permission + slot-index re-check for the "Select Kyt" flow, same shape as {@link #setGarrisonSlot}'s own inline checks. */
    private static boolean canManageGuardhouseSlot(ServerLevel serverLevel, ServerPlayer player, net.minecraft.core.BlockPos guardhousePos, int slotIndex) {
        if (!(serverLevel.getBlockEntity(guardhousePos) instanceof com.github.cerealklla.settlemynts.guardhouse.GuardhouseBlockEntity guardhouse)
                || guardhouse.settlementCoreId() == null
                || !(serverLevel.getEntity(guardhouse.settlementCoreId()) instanceof GhostTownHallCoreEntity core)) {
            return false;
        }
        PlotRecord plot = core.getPlots().stream().filter(p -> p.plotId().equals(guardhouse.plotId())).findFirst().orElse(null);
        if (plot == null || !PlotPermissions.canManage(plot, core, player.getUUID())) {
            return false;
        }
        return slotIndex >= 0 && slotIndex < com.github.cerealklla.settlemynts.guardhouse.GuardhouseConstants.MAX_GARRISON_SIZE;
    }

    /** "Plot Details" button click -- any plot's own sign, read-only, no permission check needed. */
    private static void requestPlotDetails(com.github.cerealklla.settlemynts.plotsign.RequestPlotDetailsPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!(serverLevel.getBlockEntity(payload.signPos()) instanceof com.github.cerealklla.settlemynts.plotsign.PlotConfigSignBlockEntity sign)
                || sign.settlementCoreId() == null
                || !(serverLevel.getEntity(sign.settlementCoreId()) instanceof GhostTownHallCoreEntity core)) {
            player.sendSystemMessage(Component.literal("Couldn't resolve this plot anymore."));
            return;
        }
        PlotRecord plot = core.getPlots().stream().filter(p -> p.plotId().equals(sign.plotId())).findFirst().orElse(null);
        if (plot == null) {
            player.sendSystemMessage(Component.literal("Couldn't resolve this plot anymore."));
            return;
        }
        com.github.cerealklla.settlemynts.bills.PlotBillingSummary summary = com.github.cerealklla.settlemynts.bills.PlotBillingSummary.summarize(serverLevel, plot);
        PacketDistributor.sendToPlayer(player, new com.github.cerealklla.settlemynts.plotsign.OpenPlotDetailsPayload(
                new com.github.cerealklla.settlemynts.plotsign.PlotSummaryEntry(summary.plotName(), summary.ownerDisplay(), summary.billingStanding(), summary.issue())));
    }

    /** "Enter Shop"/"Manage Shop" click -- lazily registers a Shop for the plot on the first "Manage Shop" (2026-10-05). */
    private static void requestShop(com.github.cerealklla.settlemynts.plotsign.RequestShopPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        handleRequestShop(player, payload.anchor(), payload.manage());
    }

    /**
     * Real body of "Enter Shop"/"Manage Shop," extracted 2026-10-08 so {@code
     * resident.VillagerShopInteractListener} (a plain right-click on a natural village's trading
     * Villager, Part C of the "Natural settlements..." plan) can open the exact same Shop screen a
     * Plot Config Sign's "Enter Shop" button would, without duplicating this logic.
     */
    public static void handleRequestShop(ServerPlayer player, com.github.cerealklla.settlemynts.plotsign.ShopAnchor anchor, boolean manage) {
        if (!(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge.isAvailable()) {
            player.sendSystemMessage(Component.literal("Shop system not available (Yconomics isn't loaded)."));
            return;
        }
        java.util.Optional<ShopContext> resolved = resolveShopContext(serverLevel, anchor);
        if (resolved.isEmpty()) {
            player.sendSystemMessage(Component.literal("Couldn't resolve this plot anymore."));
            return;
        }
        PlotRecord plot = resolved.get().plot();
        com.github.cerealklla.settlemynts.zone.PlotOwner owner = resolved.get().owner();
        // A natural village's auto-generated plot has no sign/owner to manage it through -- Manage
        // Shop is simply never offered for one, regardless of what the client asked for (see
        // ShopAnchor.Npc's own doc).
        manage = manage && owner instanceof GhostTownHallCoreEntity;
        if (plot.zoneTypeId().equals(com.github.cerealklla.settlemynts.guardhouse.GuardhouseConstants.GUARDHOUSE_ZONE_TYPE_ID)
                || plot.zoneTypeId().equals(GhostTownHallCoreEntity.TOWN_HALL_ZONE_TYPE_ID)
                || (plot.shopId().isEmpty() && !manage)) {
            player.sendSystemMessage(Component.literal("This plot doesn't have a Shop."));
            return;
        }
        if (manage) {
            boolean canManage = com.github.cerealklla.settlemynts.zone.PlotPermissions.canManage(plot, (GhostTownHallCoreEntity) owner, player.getUUID());
            if (!canManage) {
                player.sendSystemMessage(Component.literal("You don't have permission to manage this Shop."));
                return;
            }
            if (plot.shopId().isEmpty()) {
                java.util.UUID shopId = com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge.registerShop(serverLevel, plot.plotId());
                plot = plot.withShopId(shopId);
                owner.updatePlot(plot);
            }
        }
        // Idempotent catalog catch-up (2026-10-05) -- adds any Zone-Type-appropriate listings not
        // yet present (including the first ones, for a plot finalized before this feature existed,
        // or a plot whose Tier has risen since it was last seeded) and tops up low stock. No-op if
        // this Zone Type has no registered catalog. See ShopSeeding's own doc.
        com.github.cerealklla.settlemynts.zone.ShopSeeding.syncToCatalog(serverLevel, plot, owner, resolved.get().anchorPos());
        UUID resolvedPlotId = plot.plotId();
        plot = owner.getPlots().stream().filter(p -> p.plotId().equals(resolvedPlotId)).findFirst().orElse(plot);

        java.util.List<com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge.ShopListingView> views = plot.shopId()
                .map(shopId -> com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge.getListings(serverLevel, shopId))
                .orElse(java.util.List.of());
        java.util.Map<net.minecraft.resources.Identifier, Integer> stock =
                com.github.cerealklla.settlemynts.api.Settlemynts.scanPlotItemStock(serverLevel, plot.plotId());
        int shopGoldNuggets = stock.getOrDefault(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(net.minecraft.world.item.Items.GOLD_NUGGET), 0);
        int playerGoldNuggets = com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge.isAvailable()
                ? com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge.getNuggetBalance(player)
                : 0;

        if (manage) {
            // Manage mode (2026-10-08, redesigned per explicit spec the same day): the plot's Shop
            // Config is the union of four sources -- (1) real listings (always shown, concrete or
            // tag), (2) whatever's physically in the plot's boxes right now, (3) every resource the
            // owner has explicitly saved as 0/blank before (plot.suppressedShopResources(), so that
            // choice is remembered and the row never vanishes or gets a default reapplied -- real
            // report: "after setting them to 0 and hitting save, the next time I open the manage
            // screen they aren't listed at all"), and (4) every item this Zone Type's catalog
            // recommends (so a brand-new plot/item shows a sensible starting price). A row's
            // suggestedPrice (a pure UI pre-fill hint, see ShopInventoryEntry's own doc) is only ever
            // non-zero when the row is neither really listed nor already explicitly suppressed.
            java.util.Map<net.minecraft.resources.Identifier, Integer> listedPriceByItem = new java.util.LinkedHashMap<>();
            java.util.List<com.github.cerealklla.settlemynts.plotsign.ShopInventoryEntry> tagRows = new java.util.ArrayList<>();
            for (com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge.ShopListingView view : views) {
                if (view.resource().tag().isPresent()) {
                    net.minecraft.tags.TagKey<net.minecraft.world.item.Item> tag = view.resource().tag().get();
                    int tagStock = stock.entrySet().stream()
                            .filter(e -> view.resource().matches(new net.minecraft.world.item.ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(e.getKey()))))
                            .mapToInt(java.util.Map.Entry::getValue).sum();
                    tagRows.add(new com.github.cerealklla.settlemynts.plotsign.ShopInventoryEntry(tag.location(), true, tagStock, view.pricePerUnit(), 0));
                } else {
                    listedPriceByItem.put(view.resource().itemId().get(), view.pricePerUnit());
                }
            }

            java.util.Set<net.minecraft.resources.Identifier> suppressedItems = plot.suppressedShopResources().stream()
                    .filter(s -> !s.isTag())
                    .map(com.github.cerealklla.settlemynts.zone.SuppressedShopResource::resourceKey)
                    .collect(java.util.stream.Collectors.toSet());

            java.util.Map<net.minecraft.resources.Identifier, Integer> catalogDefaultByItem = new java.util.LinkedHashMap<>();
            for (com.github.cerealklla.settlemynts.zone.SeedListing seed : com.github.cerealklla.settlemynts.zone.ShopSeeding.catalogSeedListings(serverLevel, plot, resolved.get().anchorPos())) {
                seed.listingResource().itemId().ifPresent(itemId -> catalogDefaultByItem.put(itemId, seed.pricePerUnit()));
            }

            java.util.Set<net.minecraft.resources.Identifier> allKeys = new java.util.LinkedHashSet<>();
            allKeys.addAll(stock.keySet());
            allKeys.addAll(listedPriceByItem.keySet());
            allKeys.addAll(suppressedItems);
            allKeys.addAll(catalogDefaultByItem.keySet());
            // Gold Nuggets are the shop's own currency, not merchandise -- never a row here, same as
            // the sell-mode listing table already (correctly) excludes it.
            allKeys.remove(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(net.minecraft.world.item.Items.GOLD_NUGGET));

            java.util.List<com.github.cerealklla.settlemynts.plotsign.ShopInventoryEntry> inventory = new java.util.ArrayList<>(tagRows);
            for (net.minecraft.resources.Identifier key : allKeys) {
                int listedPrice = listedPriceByItem.getOrDefault(key, 0);
                boolean alreadyConfigured = listedPrice > 0 || suppressedItems.contains(key);
                int suggestedPrice = alreadyConfigured ? 0 : catalogDefaultByItem.getOrDefault(key, 0);
                inventory.add(new com.github.cerealklla.settlemynts.plotsign.ShopInventoryEntry(
                        key, false, stock.getOrDefault(key, 0), listedPrice, suggestedPrice));
            }
            // In-stock items first (explicit request, 2026-10-08: "I'd like the list sorted so that
            // the items that the shop plot has in stock are always at the top"), alphabetical within
            // each group.
            inventory.sort(java.util.Comparator
                    .comparing((com.github.cerealklla.settlemynts.plotsign.ShopInventoryEntry e) -> e.shopStock() <= 0)
                    .thenComparing(com.github.cerealklla.settlemynts.plotsign.ShopInventoryEntry::label));
            PacketDistributor.sendToPlayer(player, new com.github.cerealklla.settlemynts.plotsign.OpenShopPayload(
                    anchor, plot.plotId(), true, java.util.List.of(), inventory, shopGoldNuggets, playerGoldNuggets));
            return;
        }

        double merchantBonusFraction = com.github.cerealklla.settlemynts.bridge.LyfeMerchantBridge.isLoaded()
                ? com.github.cerealklla.settlemynts.bridge.LyfeMerchantBridge.getPriceBonusFraction(player)
                : 0.0;
        java.util.List<com.github.cerealklla.settlemynts.plotsign.ShopListingEntry> listings = views.stream()
                .map(l -> {
                    net.minecraft.resources.Identifier key = l.resource().tag().map(t -> t.location()).orElseGet(() -> l.resource().itemId().get());
                    boolean isTag = l.resource().tag().isPresent();
                    int shopStock = isTag
                            ? stock.entrySet().stream()
                                .filter(e -> l.resource().matches(new net.minecraft.world.item.ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(e.getKey()))))
                                .mapToInt(java.util.Map.Entry::getValue).sum()
                            : stock.getOrDefault(key, 0);
                    int effectiveBuyPrice = com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge.effectiveBuyCost(l.pricePerUnit(), merchantBonusFraction);
                    int effectiveSellPrice = com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge.effectiveSellPayout(l.pricePerUnit(), merchantBonusFraction);
                    return new com.github.cerealklla.settlemynts.plotsign.ShopListingEntry(
                            key, isTag, l.pricePerUnit(), l.buyPricePerUnit(), shopStock, effectiveBuyPrice, effectiveSellPrice);
                })
                .sorted(java.util.Comparator
                        .comparing((com.github.cerealklla.settlemynts.plotsign.ShopListingEntry e) -> e.shopStock() <= 0)
                        .thenComparing(com.github.cerealklla.settlemynts.plotsign.ShopListingEntry::label))
                .toList();
        PacketDistributor.sendToPlayer(player, new com.github.cerealklla.settlemynts.plotsign.OpenShopPayload(
                anchor, plot.plotId(), false, listings, java.util.List.of(), shopGoldNuggets, playerGoldNuggets));
    }

    /** "Buy N" click on the real Shop screen -- charges the buyer only for whatever was actually filled. */
    private static void buyFromShop(com.github.cerealklla.settlemynts.plotsign.BuyFromShopPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        java.util.Optional<ShopContext> resolvedBuy = resolveShopContext(serverLevel, payload.anchor());
        if (resolvedBuy.isEmpty()) {
            player.sendSystemMessage(Component.literal("Couldn't resolve this plot anymore."));
            return;
        }
        UUID plotId = resolvedBuy.get().plot().plotId();
        java.util.Optional<UUID> shopId = resolvedBuy.get().plot().shopId();
        if (shopId.isEmpty()) {
            player.sendSystemMessage(Component.literal("This plot has no Shop."));
            return;
        }
        com.github.cerealklla.settlemynts.zone.ShopResource resource = payload.isTag()
                ? com.github.cerealklla.settlemynts.zone.ShopResource.ofTag(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, payload.resourceKey()))
                : com.github.cerealklla.settlemynts.zone.ShopResource.ofItem(payload.resourceKey());
        java.util.List<net.minecraft.world.Container> boxes = com.github.cerealklla.settlemynts.api.Settlemynts.resolvePlotBoxes(serverLevel, plotId);
        com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge.PurchaseResult result =
                com.github.cerealklla.settlemynts.api.Settlemynts.purchaseFromSettlementShop(serverLevel, plotId, shopId.get(), resource, payload.quantity(), boxes);
        if (result.filled() <= 0) {
            player.sendSystemMessage(Component.literal("Out of stock."));
            return;
        }
        if (!com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge.withdrawNuggets(player, result.nuggetsCharged())) {
            // Stock was already drained/paid into the shop's own boxes above -- this shouldn't
            // normally happen since the buyer presumably has the nuggets to click "Buy" at all, but
            // if it does, refund isn't attempted here (a known, flagged simplification -- see
            // decisions.md) since under-stocked/under-funded purchases are expected to be rare.
            player.sendSystemMessage(Component.literal("You don't have enough Gold Nuggets."));
            return;
        }
        // 2026-10-06 fix (real report: "bought items from a Settlement shop, it took my gold but
        // never gave me the item") -- the stock drained from the shop's boxes was never actually
        // delivered anywhere. Give every drained stack to the buyer now, falling back to dropping it
        // at their feet if their inventory is full (same `add`-then-`placeItemBackInInventory`
        // fallback Blueprynts' own FootprintSlabGuard already uses).
        for (net.minecraft.world.item.ItemStack stack : result.itemsReceived()) {
            if (!player.getInventory().add(stack)) {
                player.getInventory().placeItemBackInInventory(stack);
            }
        }
        // Merchant skill XP for the buyer (2026-10-08, real report: "not gaining merchant xp for
        // buying from a shop") -- Lyfe's own MerchantListener only ever grants XP from
        // TradeWithVillagerEvent (NPC trades), which never fires for a player Shop purchase. 1:1
        // XP per nugget charged, matching that listener's own baseline-value convention.
        if (com.github.cerealklla.settlemynts.bridge.LyfeMerchantBridge.isLoaded()) {
            com.github.cerealklla.settlemynts.bridge.LyfeMerchantBridge.grantMerchantXp(player, result.nuggetsCharged());
        }
        // Merchant skill price bonus, as a buy-side rebate (2026-10-08) -- same per-player formula
        // MerchantListener already applies to vanilla NPC trades, and the same "rebate after a
        // full-price charge" mechanism its own sell-side bonus already uses (there's no per-offer
        // discount field here to adjust beforehand, unlike a real MerchantOffer) -- the shop's own
        // payment boxes still receive the real listing price in full, same as any other buyer.
        // Computed per-unit via ShopPricing.effectiveBuyCost (not a flat % of the total charged) --
        // real bug found same day: a flat-fraction rebate here independent of the sell-side bonus
        // formula could round to the exact same number as the sell-side payout at certain prices
        // (confirmed: sell price 3, max Merchant level -> both landed on 2), letting a skilled
        // Merchant buy and immediately sell back at zero net cost, for infinite free XP. effectiveBuyCost/
        // effectiveSellPayout are a matched pair that guarantee the sell payout is always strictly
        // less than the buy cost for the same price/bonus, closing that loop by construction.
        int rebate = 0;
        if (com.github.cerealklla.settlemynts.bridge.LyfeMerchantBridge.isLoaded() && result.filled() > 0) {
            int pricePerUnit = result.nuggetsCharged() / result.filled();
            double fraction = com.github.cerealklla.settlemynts.bridge.LyfeMerchantBridge.getPriceBonusFraction(player);
            int effectiveCostPerUnit = com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge.effectiveBuyCost(pricePerUnit, fraction);
            rebate = Math.max(0, result.nuggetsCharged() - effectiveCostPerUnit * result.filled());
        }
        if (rebate > 0) {
            net.minecraft.world.item.ItemStack reward = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GOLD_NUGGET, rebate);
            if (!player.getInventory().add(reward)) {
                player.drop(reward, false);
            }
        }
        player.sendSystemMessage(Component.literal("Bought " + result.filled() + " for " + result.nuggetsCharged() + " nuggets."));
    }

    /**
     * "Sell N" click on the real Shop screen (2026-10-08) -- the reverse of {@link #buyFromShop}:
     * sells a matching item back to the shop out of the seller's own inventory. Originally required
     * holding the item in the main hand; changed same day per explicit request ("selling should not
     * require you to have the item in your hand, just take it out of the players inventory excluding
     * from within bundles/chests within the player inventory") to scan the player's whole {@code
     * Inventory} ({@code getContainerSize()}/{@code getItem}, the plain 36 hotbar+main slots -- this
     * MC version's equipment (armor/offhand) lives in a separate {@code EntityEquipment} component,
     * not this container, so it's naturally excluded too) and pull from however many stacks are
     * needed to cover the sale. A slot's own item id is all that's ever matched against -- a Bundle
     * or Shulker Box sitting in the inventory is just one stack of its own item type, so its *nested*
     * contents are never inspected or touched, satisfying the exclusion without any special-case
     * logic.
     */
    private static void sellToShop(com.github.cerealklla.settlemynts.plotsign.SellToShopPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        java.util.Optional<ShopContext> resolvedSell = resolveShopContext(serverLevel, payload.anchor());
        if (resolvedSell.isEmpty()) {
            player.sendSystemMessage(Component.literal("Couldn't resolve this plot anymore."));
            return;
        }
        UUID plotId = resolvedSell.get().plot().plotId();
        java.util.Optional<UUID> shopId = resolvedSell.get().plot().shopId();
        if (shopId.isEmpty()) {
            player.sendSystemMessage(Component.literal("This plot has no Shop."));
            return;
        }
        com.github.cerealklla.settlemynts.zone.ShopResource resource = payload.isTag()
                ? com.github.cerealklla.settlemynts.zone.ShopResource.ofTag(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, payload.resourceKey()))
                : com.github.cerealklla.settlemynts.zone.ShopResource.ofItem(payload.resourceKey());
        net.minecraft.world.entity.player.Inventory inventory = player.getInventory();
        net.minecraft.resources.Identifier itemId = null;
        int available = 0;
        java.util.List<Integer> matchingSlots = new java.util.ArrayList<>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            net.minecraft.world.item.ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !resource.matches(stack)) {
                continue;
            }
            if (itemId == null) {
                itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
            }
            available += stack.getCount();
            matchingSlots.add(slot);
        }
        if (itemId == null || available <= 0) {
            player.sendSystemMessage(Component.literal("You don't have any of that to sell."));
            return;
        }
        int quantity = Math.min(payload.quantity(), available);
        com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge.SellResult result =
                com.github.cerealklla.settlemynts.api.Settlemynts.sellToSettlementShop(serverLevel, plotId, shopId.get(), resource, itemId, quantity);
        if (result.itemsSold() <= 0) {
            player.sendSystemMessage(Component.literal("The shop can't afford to buy that right now."));
            return;
        }
        int remaining = result.itemsSold();
        for (int slot : matchingSlots) {
            if (remaining <= 0) {
                break;
            }
            int take = Math.min(remaining, inventory.getItem(slot).getCount());
            inventory.removeItem(slot, take);
            remaining -= take;
        }
        int nuggetsOwed = result.nuggetsReceived();
        // Merchant skill price bonus, sell-side (2026-10-08) -- same mechanism/formula as
        // MerchantListener's own vanilla-NPC sell bonus: extra nuggets paid directly, since there's
        // no per-offer field to boost a fixed result (see that class's own doc for why). Computed
        // per-unit via ShopPricing.effectiveSellPayout (not a flat % bonus on top of the base payout)
        // -- that method guarantees the result is always strictly less than what the SAME player
        // would currently pay to buy this item back, closing a real zero-cost buy/sell XP loop a
        // naive flat-% bonus here would otherwise allow (see buyFromShop's own comment for the exact
        // rounding case found).
        if (com.github.cerealklla.settlemynts.bridge.LyfeMerchantBridge.isLoaded()) {
            double fraction = com.github.cerealklla.settlemynts.bridge.LyfeMerchantBridge.getPriceBonusFraction(player);
            int effectivePayoutPerUnit = com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge.effectiveSellPayout(result.sellPricePerUnit(), fraction);
            nuggetsOwed = effectivePayoutPerUnit * result.itemsSold();
        }
        net.minecraft.world.item.ItemStack payment = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GOLD_NUGGET, nuggetsOwed);
        if (!player.getInventory().add(payment)) {
            player.drop(payment, false);
        }
        if (com.github.cerealklla.settlemynts.bridge.LyfeMerchantBridge.isLoaded()) {
            com.github.cerealklla.settlemynts.bridge.LyfeMerchantBridge.grantMerchantXp(player, nuggetsOwed);
        }
        player.sendSystemMessage(Component.literal("Sold " + result.itemsSold() + " for " + nuggetsOwed + " nuggets."));
    }

    /**
     * "Save Changes" on {@code client.ManageShopScreen} (2026-10-08, replacing the old held-item-add
     * plus price +/-1/+/-10 click-spam flow entirely -- real feedback: "having to close the UI, put
     * something in your hand, then go to the manage screen to add an item is clunky"). One batch
     * covering every row the owner saw. {@code price <= 0} removes any existing listing for that
     * resource and marks it as explicitly suppressed (persisted on {@link PlotRecord}, see {@link
     * com.github.cerealklla.settlemynts.zone.SuppressedShopResource}'s own doc) so {@code
     * #requestShop}'s row assembly remembers this choice instead of reapplying a default the next
     * time Manage Shop opens; {@code price > 0} sets/creates the listing at that exact price and
     * clears any stale suppression marker -- floor/never-equal-buy-sell enforcement happens inside
     * {@code shop.ShopListing}'s own constructor either way.
     */
    private static void setShopListings(com.github.cerealklla.settlemynts.plotsign.SetShopListingsPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        java.util.Optional<PlotRecordAndCore> resolved = resolvePlotForManage(serverLevel, payload.signPos(), player.getUUID());
        if (resolved.isEmpty()) {
            player.sendSystemMessage(Component.literal("Couldn't resolve this plot, or you can't manage it."));
            return;
        }
        PlotRecord plot = resolved.get().plot();
        if (plot.shopId().isEmpty()) {
            player.sendSystemMessage(Component.literal("This Shop isn't registered yet -- reopen the menu first."));
            return;
        }
        UUID shopId = plot.shopId().get();
        java.util.List<com.github.cerealklla.settlemynts.zone.SuppressedShopResource> suppressed =
                new java.util.ArrayList<>(plot.suppressedShopResources());
        boolean suppressionChanged = false;
        for (com.github.cerealklla.settlemynts.plotsign.ShopListingUpdate update : payload.updates()) {
            com.github.cerealklla.settlemynts.zone.ShopResource resource = update.isTag()
                    ? com.github.cerealklla.settlemynts.zone.ShopResource.ofTag(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, update.resourceKey()))
                    : com.github.cerealklla.settlemynts.zone.ShopResource.ofItem(update.resourceKey());
            com.github.cerealklla.settlemynts.zone.SuppressedShopResource marker =
                    new com.github.cerealklla.settlemynts.zone.SuppressedShopResource(update.resourceKey(), update.isTag());
            if (update.price() <= 0) {
                com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge.removeListing(serverLevel, shopId, resource);
                if (!suppressed.contains(marker)) {
                    suppressed.add(marker);
                    suppressionChanged = true;
                }
            } else {
                com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge.setListingPrice(serverLevel, shopId, resource, update.price());
                if (suppressed.remove(marker)) {
                    suppressionChanged = true;
                }
            }
        }
        if (suppressionChanged) {
            resolved.get().core().updatePlot(plot.withSuppressedShopResources(suppressed));
        }
        player.sendSystemMessage(Component.literal("Shop listings updated."));
    }

    /**
     * "Planned Inventory" button -- row assembly mirrors {@code requestShop}'s own manage-mode union
     * (live box stock + existing config + catalog candidates), see {@code OpenPlannedInventoryPayload}'s
     * own doc.
     */
    private static void requestPlannedInventory(com.github.cerealklla.settlemynts.plotsign.RequestPlannedInventoryPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        java.util.Optional<PlotRecordAndCore> resolved = resolvePlotForManage(serverLevel, payload.signPos(), player.getUUID());
        if (resolved.isEmpty()) {
            player.sendSystemMessage(Component.literal("Couldn't resolve this plot, or you can't manage it."));
            return;
        }
        PlotRecord plot = resolved.get().plot();
        java.util.Map<net.minecraft.resources.Identifier, Integer> stock =
                com.github.cerealklla.settlemynts.api.Settlemynts.scanPlotItemStock(serverLevel, plot.plotId());
        stock.remove(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(net.minecraft.world.item.Items.GOLD_NUGGET));

        java.util.Map<net.minecraft.resources.Identifier, com.github.cerealklla.settlemynts.zone.PlannedInventoryTarget> targetsByItem = new java.util.LinkedHashMap<>();
        java.util.List<com.github.cerealklla.settlemynts.zone.PlannedInventoryTarget> tagTargets = new java.util.ArrayList<>();
        for (com.github.cerealklla.settlemynts.zone.PlannedInventoryTarget target : plot.plannedInventory()) {
            if (target.isTag()) {
                tagTargets.add(target);
            } else {
                targetsByItem.put(target.resourceKey(), target);
            }
        }

        java.util.List<com.github.cerealklla.settlemynts.plotsign.PlannedInventoryEntry> entries = new java.util.ArrayList<>();
        for (com.github.cerealklla.settlemynts.zone.PlannedInventoryTarget target : tagTargets) {
            net.minecraft.tags.TagKey<net.minecraft.world.item.Item> tag =
                    net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, target.resourceKey());
            com.github.cerealklla.settlemynts.zone.ShopResource resource = com.github.cerealklla.settlemynts.zone.ShopResource.ofTag(tag);
            int tagStock = stock.entrySet().stream()
                    .filter(e -> resource.matches(new net.minecraft.world.item.ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(e.getKey()))))
                    .mapToInt(java.util.Map.Entry::getValue).sum();
            entries.add(new com.github.cerealklla.settlemynts.plotsign.PlannedInventoryEntry(target.resourceKey(), true, tagStock, target.targetCount()));
        }

        java.util.Set<net.minecraft.resources.Identifier> itemKeys = new java.util.LinkedHashSet<>();
        itemKeys.addAll(stock.keySet());
        itemKeys.addAll(targetsByItem.keySet());
        for (com.github.cerealklla.settlemynts.zone.SeedListing seed :
                com.github.cerealklla.settlemynts.zone.ShopSeeding.catalogSeedListings(serverLevel, plot, payload.signPos())) {
            seed.listingResource().itemId().ifPresent(itemKeys::add);
        }
        for (net.minecraft.resources.Identifier key : itemKeys) {
            com.github.cerealklla.settlemynts.zone.PlannedInventoryTarget existing = targetsByItem.get(key);
            int targetCount = existing != null ? existing.targetCount() : -1;
            entries.add(new com.github.cerealklla.settlemynts.plotsign.PlannedInventoryEntry(key, false, stock.getOrDefault(key, 0), targetCount));
        }
        entries.sort(java.util.Comparator
                .comparing((com.github.cerealklla.settlemynts.plotsign.PlannedInventoryEntry e) -> e.currentStock() <= 0)
                .thenComparing(com.github.cerealklla.settlemynts.plotsign.PlannedInventoryEntry::label));

        PacketDistributor.sendToPlayer(player, new com.github.cerealklla.settlemynts.plotsign.OpenPlannedInventoryPayload(payload.signPos(), entries));
    }

    /** "Save Changes" on {@code client.PlannedInventoryScreen}. */
    private static void setPlannedInventory(com.github.cerealklla.settlemynts.plotsign.SetPlannedInventoryPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        java.util.Optional<PlotRecordAndCore> resolved = resolvePlotForManage(serverLevel, payload.signPos(), player.getUUID());
        if (resolved.isEmpty()) {
            player.sendSystemMessage(Component.literal("Couldn't resolve this plot, or you can't manage it."));
            return;
        }
        java.util.List<com.github.cerealklla.settlemynts.zone.PlannedInventoryTarget> targets = new java.util.ArrayList<>();
        for (com.github.cerealklla.settlemynts.plotsign.PlannedInventoryUpdate update : payload.updates()) {
            if (update.managed()) {
                targets.add(new com.github.cerealklla.settlemynts.zone.PlannedInventoryTarget(
                        update.resourceKey(), update.isTag(), Math.max(0, update.targetCount())));
            }
        }
        resolved.get().core().updatePlot(resolved.get().plot().withPlannedInventory(targets));
        player.sendSystemMessage(Component.literal("Planned Inventory updated."));
    }

    private record PlotRecordAndCore(PlotRecord plot, GhostTownHallCoreEntity core) {
    }

    /**
     * Resolves either {@link com.github.cerealklla.settlemynts.plotsign.ShopAnchor} variant to a real
     * plot -- {@link com.github.cerealklla.settlemynts.plotsign.ShopAnchor.Sign} the existing
     * Plot-Config-Sign-block path, {@link com.github.cerealklla.settlemynts.plotsign.ShopAnchor.Npc}
     * (added 2026-10-08, Part C of the "Natural settlements..." plan) a tagged trading Villager
     * resolved via its persistent {@code settlemynts_plot_id} tag ({@code
     * zone.NaturalVillagePlotGenerator#PLOT_ID_TAG}) back through {@code
     * zone.NaturalSettlementPlotStore#findByPlotId}.
     */
    private record ShopContext(PlotRecord plot, com.github.cerealklla.settlemynts.zone.PlotOwner owner, net.minecraft.core.BlockPos anchorPos) {
    }

    private static java.util.Optional<ShopContext> resolveShopContext(ServerLevel level, com.github.cerealklla.settlemynts.plotsign.ShopAnchor anchor) {
        if (anchor instanceof com.github.cerealklla.settlemynts.plotsign.ShopAnchor.Sign sign) {
            net.minecraft.core.BlockPos pos = sign.pos();
            if (!(level.getBlockEntity(pos) instanceof com.github.cerealklla.settlemynts.plotsign.PlotConfigSignBlockEntity signEntity)
                    || signEntity.settlementCoreId() == null
                    || !(level.getEntity(signEntity.settlementCoreId()) instanceof GhostTownHallCoreEntity core)) {
                return java.util.Optional.empty();
            }
            PlotRecord plot = core.getPlots().stream().filter(p -> p.plotId().equals(signEntity.plotId())).findFirst().orElse(null);
            if (plot == null) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new ShopContext(plot, core, pos));
        }
        com.github.cerealklla.settlemynts.plotsign.ShopAnchor.Npc npc = (com.github.cerealklla.settlemynts.plotsign.ShopAnchor.Npc) anchor;
        // Player settlement's own NPC-plot worker (2026-10-09) -- resolved through the same
        // settlementCoreId/plotId fields every PlotNpc already carries natively, mirroring the Sign
        // case above, rather than the natural-village Npc path below (which depends on a persistent-
        // data tag and NaturalSettlementPlotStore, neither of which a PlotNpc has/uses).
        if (level.getEntity(npc.entityId()) instanceof com.github.cerealklla.settlemynts.zone.PlotNpc plotNpc
                && plotNpc.plotId() != null) {
            UUID settlementCoreId = plotNpc.settlementCoreId();
            if (settlementCoreId == null || !(level.getEntity(settlementCoreId) instanceof GhostTownHallCoreEntity core)) {
                return java.util.Optional.empty();
            }
            PlotRecord plot = core.getPlots().stream().filter(p -> p.plotId().equals(plotNpc.plotId())).findFirst().orElse(null);
            if (plot == null) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new ShopContext(plot, core, ((net.minecraft.world.entity.Entity) plotNpc).blockPosition()));
        }
        boolean foundEntity = level.getEntity(npc.entityId()) instanceof net.minecraft.world.entity.npc.villager.Villager;
        if (!foundEntity) {
            LOGGER.info("Natural village shop: Npc anchor entityId={} -- no Villager entity found at that id", npc.entityId());
            return java.util.Optional.empty();
        }
        net.minecraft.world.entity.npc.villager.Villager villager = (net.minecraft.world.entity.npc.villager.Villager) level.getEntity(npc.entityId());
        java.util.Optional<UUID> plotId = villager.getPersistentData().getIntArray(com.github.cerealklla.settlemynts.zone.NaturalVillagePlotGenerator.PLOT_ID_TAG)
                .map(net.minecraft.core.UUIDUtil::uuidFromIntArray);
        if (plotId.isEmpty()) {
            LOGGER.info("Natural village shop: Npc anchor entityId={} -- Villager found but has no plot tag", npc.entityId());
            return java.util.Optional.empty();
        }
        java.util.Optional<ShopContext> resolved = com.github.cerealklla.settlemynts.zone.NaturalSettlementPlotStore.get(level.getServer()).findByPlotId(plotId.get())
                .map(entry -> new ShopContext(entry.getValue(),
                        new com.github.cerealklla.settlemynts.zone.NaturalSettlementPlotOwner(level.getServer(), entry.getKey()),
                        villager.blockPosition()));
        if (resolved.isEmpty()) {
            LOGGER.info("Natural village shop: Npc anchor entityId={} plotId={} -- tag present but no matching plot found in the store", npc.entityId(), plotId.get());
        }
        return resolved;
    }

    private static java.util.Optional<PlotRecordAndCore> resolvePlotForManage(ServerLevel level, net.minecraft.core.BlockPos signPos, UUID playerId) {
        if (!(level.getBlockEntity(signPos) instanceof com.github.cerealklla.settlemynts.plotsign.PlotConfigSignBlockEntity sign)
                || sign.settlementCoreId() == null
                || !(level.getEntity(sign.settlementCoreId()) instanceof GhostTownHallCoreEntity core)) {
            return java.util.Optional.empty();
        }
        PlotRecord plot = core.getPlots().stream().filter(p -> p.plotId().equals(sign.plotId())).findFirst().orElse(null);
        if (plot == null || !com.github.cerealklla.settlemynts.zone.PlotPermissions.canManage(plot, core, playerId)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new PlotRecordAndCore(plot, core));
    }

    /** "Plot Management" button click -- Town Hall sign only, re-checks both {@code canManage} and the zone type itself server-side. */
    private static void requestPlotManagement(com.github.cerealklla.settlemynts.plotsign.RequestPlotManagementPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!(serverLevel.getBlockEntity(payload.signPos()) instanceof com.github.cerealklla.settlemynts.plotsign.PlotConfigSignBlockEntity sign)
                || sign.settlementCoreId() == null
                || !(serverLevel.getEntity(sign.settlementCoreId()) instanceof GhostTownHallCoreEntity core)) {
            player.sendSystemMessage(Component.literal("Couldn't resolve this plot anymore."));
            return;
        }
        PlotRecord plot = core.getPlots().stream().filter(p -> p.plotId().equals(sign.plotId())).findFirst().orElse(null);
        if (plot == null || !PlotPermissions.canManage(plot, core, player.getUUID())) {
            player.sendSystemMessage(Component.literal("You can't manage this plot."));
            return;
        }
        if (!plot.zoneTypeId().equals(GhostTownHallCoreEntity.TOWN_HALL_ZONE_TYPE_ID)) {
            player.sendSystemMessage(Component.literal("Plot Management is only available on the Town Hall plot."));
            return;
        }
        List<com.github.cerealklla.settlemynts.plotsign.PlotSummaryEntry> entries = new ArrayList<>();
        for (PlotRecord p : core.getPlots()) {
            com.github.cerealklla.settlemynts.bills.PlotBillingSummary summary = com.github.cerealklla.settlemynts.bills.PlotBillingSummary.summarize(serverLevel, p);
            entries.add(new com.github.cerealklla.settlemynts.plotsign.PlotSummaryEntry(summary.plotName(), summary.ownerDisplay(), summary.billingStanding(), summary.issue()));
        }
        PacketDistributor.sendToPlayer(player, new com.github.cerealklla.settlemynts.plotsign.OpenPlotManagementPayload(entries));
    }

    /**
     * "Upgrade Plot" button click -- computes a live cost/affordability preview (see {@code
     * construction.PlotTierUpgradeFunding#preview}) and sends it back so {@code
     * client.UpgradePlotScreen} can show real current-on-plot amounts and live gold costs instead of
     * a static client-computed table (added 2026-10-09, explicit follow-up request after the first
     * live test: "The listed cost should have (#) next to each to show how much is currently in the
     * plot, then the two money options should show the live gold cost... If not enough resources are
     * available... the appropriate buttons should be disabled").
     */
    private static void requestUpgradePlotPreview(com.github.cerealklla.settlemynts.plotsign.RequestUpgradePlotPreviewPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!(serverLevel.getBlockEntity(payload.signPos()) instanceof com.github.cerealklla.settlemynts.plotsign.PlotConfigSignBlockEntity sign)
                || sign.settlementCoreId() == null
                || !(serverLevel.getEntity(sign.settlementCoreId()) instanceof GhostTownHallCoreEntity core)) {
            player.sendSystemMessage(Component.literal("Couldn't resolve this plot anymore."));
            return;
        }
        PlotRecord plot = core.getPlots().stream().filter(p -> p.plotId().equals(sign.plotId())).findFirst().orElse(null);
        if (plot == null || !PlotPermissions.canManage(plot, core, player.getUUID())) {
            player.sendSystemMessage(Component.literal("You can't manage this plot."));
            return;
        }
        int maxPlotTier = plot.zoneTypeId().equals(GhostTownHallCoreEntity.TOWN_HALL_ZONE_TYPE_ID)
                ? 5 : core.townHallPlot().map(PlotRecord::tier).orElse(5);
        if (plot.constructionBoxId().isEmpty() || plot.tier() >= 5 || plot.tier() >= maxPlotTier) {
            return; // Button shouldn't have been shown at all -- no message needed, same as other stale-state guards.
        }
        com.github.cerealklla.settlemynts.construction.PlotTierUpgradeFunding.Preview preview =
                com.github.cerealklla.settlemynts.construction.PlotTierUpgradeFunding.preview(player, serverLevel, plot, core.getUUID(), payload.signPos());
        List<com.github.cerealklla.settlemynts.plotsign.UpgradePlotPreviewPayload.ResourceRow> rows = new ArrayList<>();
        for (var entry : preview.resources()) {
            rows.add(new com.github.cerealklla.settlemynts.plotsign.UpgradePlotPreviewPayload.ResourceRow(
                    entry.label(), entry.amount(), entry.onHand(), entry.mixCost(), entry.mixFullyCovered(), entry.goldCost(), entry.goldFullyCovered()));
        }
        int flags = com.github.cerealklla.settlemynts.plotsign.UpgradePlotPreviewPayload.packFlags(
                preview.onHandEnabled(), preview.mixEnabled(), preview.goldEnabled());
        PacketDistributor.sendToPlayer(player, new com.github.cerealklla.settlemynts.plotsign.UpgradePlotPreviewPayload(
                payload.signPos(), plot.tier() + 1, rows,
                preview.mixTotalCost(), preview.goldTotalCost(), flags, preview.goldOnPlot(), preview.goldOnPerson(),
                preview.newlyAllowedZoneTypeLabels()));
    }

    /**
     * "Upgrade Plot" funding button click (2026-10-09, explicit request: "same options as upgrading
     * structures") -- re-resolves the plot/{@code canManage} server-side, same precedent as every
     * other sign button, then funds raising {@code PlotRecord#tier} by one via {@code
     * construction.PlotTierUpgradeFunding}. Deliberately never touches the Construction Box or its
     * bound Blueprint -- see {@code zone.PlotRecord#tier}'s own class doc for why picking/rebuilding
     * at a given Tier is a separate action (and cost) done through the Construction Box's own menu
     * instead.
     */
    private static void requestUpgradePlot(com.github.cerealklla.settlemynts.plotsign.RequestUpgradePlotPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!(serverLevel.getBlockEntity(payload.signPos()) instanceof com.github.cerealklla.settlemynts.plotsign.PlotConfigSignBlockEntity sign)
                || sign.settlementCoreId() == null
                || !(serverLevel.getEntity(sign.settlementCoreId()) instanceof GhostTownHallCoreEntity core)) {
            player.sendSystemMessage(Component.literal("Couldn't resolve this plot anymore."));
            return;
        }
        PlotRecord plot = core.getPlots().stream().filter(p -> p.plotId().equals(sign.plotId())).findFirst().orElse(null);
        if (plot == null || !PlotPermissions.canManage(plot, core, player.getUUID())) {
            player.sendSystemMessage(Component.literal("You can't manage this plot."));
            return;
        }
        if (plot.constructionBoxId().isEmpty()) {
            player.sendSystemMessage(Component.literal("This plot has no Construction Box to upgrade."));
            return;
        }
        if (plot.tier() >= 5) {
            player.sendSystemMessage(Component.literal("This plot is already at the maximum Tier."));
            return;
        }
        boolean isTownHall = plot.zoneTypeId().equals(GhostTownHallCoreEntity.TOWN_HALL_ZONE_TYPE_ID);
        int maxPlotTier = isTownHall ? 5 : core.townHallPlot().map(PlotRecord::tier).orElse(5);
        if (plot.tier() >= maxPlotTier) {
            player.sendSystemMessage(Component.literal("This plot can't be upgraded past the Town Hall's own Tier (" + maxPlotTier + ") yet."));
            return;
        }
        com.github.cerealklla.settlemynts.construction.PlotTierUpgradeFunding.Result result =
                com.github.cerealklla.settlemynts.construction.PlotTierUpgradeFunding.fund(
                        player, serverLevel, plot, core.getUUID(), payload.signPos(), payload.option());
        if (!result.success()) {
            player.sendSystemMessage(Component.literal(result.message()));
            return;
        }
        int newTier = plot.tier() + 1;
        core.updatePlot(plot.withTier(newTier));
        if (net.neoforged.fml.ModList.get().isLoaded("blueprynts")) {
            com.github.cerealklla.settlemynts.bridge.BlueprintsConstructionBridge.setAllowedTier(serverLevel, plot.constructionBoxId().get(), newTier);
        }
        grantMayorSkillXpForPlotUpgrade(serverLevel.getServer(), core, newTier);
        player.sendSystemMessage(Component.literal("Plot upgraded to Tier " + newTier + "! Use the Construction Box to build at the new Tier."));
    }

    /**
     * "Gains XP any time a plot in a Settlement you are the mayor of is upgraded. Gain half xp if you
     * are a Town Planner for that town but not the Mayor" (Lyfe's Mayor skill, added 2026-10-09) --
     * passive, so this always grants to every qualifying player regardless of who actually clicked
     * "Upgrade Plot," not just the acting player.
     */
    private static void grantMayorSkillXpForPlotUpgrade(net.minecraft.server.MinecraftServer server, GhostTownHallCoreEntity core, int newTier) {
        if (!com.github.cerealklla.settlemynts.bridge.LyfeMayorBridge.isLoaded()) {
            return;
        }
        int baseXp = com.github.cerealklla.settlemynts.bridge.LyfeMayorBridge.xpForPlotUpgrade(newTier);
        if (baseXp <= 0) {
            return;
        }
        UUID founderId = core.getFounderId();
        if (founderId != null) {
            com.github.cerealklla.settlemynts.bridge.LyfeMayorBridge.grantMayorXp(server, founderId, baseXp);
        }
        for (UUID plannerId : core.getTownPlanners()) {
            if (!plannerId.equals(founderId)) {
                com.github.cerealklla.settlemynts.bridge.LyfeMayorBridge.grantMayorXp(server, plannerId, baseXp / 2);
            }
        }
    }

    /** "Configure Garrison" button click -- re-resolves the plot/Guardhouse and re-checks {@code zone.PlotPermissions#canManage} server-side rather than trusting the client's earlier flag, same precedent as {@code relocatePlotSign}. */
    private static void requestConfigureGarrison(com.github.cerealklla.settlemynts.guardhouse.RequestConfigureGarrisonPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!(serverLevel.getBlockEntity(payload.signPos()) instanceof com.github.cerealklla.settlemynts.plotsign.PlotConfigSignBlockEntity sign)
                || sign.settlementCoreId() == null
                || !(serverLevel.getEntity(sign.settlementCoreId()) instanceof GhostTownHallCoreEntity core)) {
            player.sendSystemMessage(Component.literal("Couldn't resolve this plot anymore."));
            return;
        }
        PlotRecord plot = core.getPlots().stream().filter(p -> p.plotId().equals(sign.plotId())).findFirst().orElse(null);
        if (plot == null || !PlotPermissions.canManage(plot, core, player.getUUID())) {
            player.sendSystemMessage(Component.literal("You can't manage this plot."));
            return;
        }
        if (!plot.zoneTypeId().equals(com.github.cerealklla.settlemynts.guardhouse.GuardhouseConstants.GUARDHOUSE_ZONE_TYPE_ID)) {
            player.sendSystemMessage(Component.literal("This plot has no garrison."));
            return;
        }
        var guardhousePos = com.github.cerealklla.settlemynts.guardhouse.GuardhouseIndex.get(serverLevel.getServer()).get(plot.plotId());
        if (guardhousePos.isEmpty() || !(serverLevel.getBlockEntity(guardhousePos.get().pos()) instanceof com.github.cerealklla.settlemynts.guardhouse.GuardhouseBlockEntity guardhouse)) {
            player.sendSystemMessage(Component.literal("This plot's Guardhouse isn't loaded right now."));
            return;
        }
        int tier = plot.boxPos().isPresent() && ModList.get().isLoaded("blueprynts")
                ? com.github.cerealklla.blueprynts.api.Blueprynts.getConstructionBoxTier(serverLevel, plot.boxPos().get()).orElse(0)
                : 0;
        int capacity = com.github.cerealklla.settlemynts.guardhouse.GuardhouseConstants.capacityForTier(tier);
        PacketDistributor.sendToPlayer(player, new com.github.cerealklla.settlemynts.guardhouse.OpenConfigureGarrisonPayload(
                guardhousePos.get().pos(), tier, capacity, guardhouse.garrisonSlots(),
                com.github.cerealklla.settlemynts.bridge.KytGuardhouseBridge.isLoaded()));
    }

    /** One garrison slot row changed on {@code client.ConfigureGarrisonScreen} -- re-checks permission the same way, and re-derives the current Tier server-side so the clamp in {@code GuardhouseBlockEntity#setGarrisonSlot} is never trusting a stale client-held Tier. */
    private static void setGarrisonSlot(com.github.cerealklla.settlemynts.guardhouse.SetGarrisonSlotPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!(serverLevel.getBlockEntity(payload.guardhousePos()) instanceof com.github.cerealklla.settlemynts.guardhouse.GuardhouseBlockEntity guardhouse)
                || guardhouse.settlementCoreId() == null
                || !(serverLevel.getEntity(guardhouse.settlementCoreId()) instanceof GhostTownHallCoreEntity core)) {
            return;
        }
        PlotRecord plot = core.getPlots().stream().filter(p -> p.plotId().equals(guardhouse.plotId())).findFirst().orElse(null);
        if (plot == null || !PlotPermissions.canManage(plot, core, player.getUUID())) {
            return;
        }
        if (payload.slotIndex() < 0 || payload.slotIndex() >= com.github.cerealklla.settlemynts.guardhouse.GuardhouseConstants.MAX_GARRISON_SIZE) {
            return;
        }
        int tier = plot.boxPos().isPresent() && ModList.get().isLoaded("blueprynts")
                ? com.github.cerealklla.blueprynts.api.Blueprynts.getConstructionBoxTier(serverLevel, plot.boxPos().get()).orElse(0)
                : 0;
        guardhouse.setGarrisonSlot(payload.slotIndex(),
                new com.github.cerealklla.settlemynts.guardhouse.GarrisonSlot(payload.maxGearTier(), payload.allowNeighborPurchase(),
                        guardhouse.garrisonSlot(payload.slotIndex()).kytLoadoutName()),
                tier);
    }

    /**
     * "Relocate Plot Sign" (design doc Section 14a) -- mirrors {@code BluepryntsMod}'s own
     * "Reposition Supply Box" handler shape. Re-checks {@link
     * com.github.cerealklla.settlemynts.zone.PlotPermissions#canManage} server-side rather than
     * trusting the client's earlier {@code canManage} flag.
     */
    private static void relocatePlotSign(com.github.cerealklla.settlemynts.plotsign.RelocatePlotSignPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        // Every branch below sends a real message on failure now (real playtest report, 2026-09-30:
        // "Relocate Plot sign initially didn't do anything" -- the original version silently
        // `return`ed on any of these, giving the player nothing to go on).
        if (!(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!(serverLevel.getBlockEntity(payload.signPos()) instanceof com.github.cerealklla.settlemynts.plotsign.PlotConfigSignBlockEntity sign)) {
            player.sendSystemMessage(Component.literal("That Plot Config Sign isn't there anymore -- try right-clicking it again."));
            return;
        }
        if (sign.settlementCoreId() == null || !(serverLevel.getEntity(sign.settlementCoreId()) instanceof GhostTownHallCoreEntity core)) {
            player.sendSystemMessage(Component.literal("This sign's settlement is gone."));
            return;
        }
        PlotRecord plot = core.getPlots().stream().filter(p -> p.plotId().equals(sign.plotId())).findFirst().orElse(null);
        if (plot == null) {
            player.sendSystemMessage(Component.literal("This sign's plot record is gone."));
            return;
        }
        if (!com.github.cerealklla.settlemynts.zone.PlotPermissions.canManage(plot, core, player.getUUID())) {
            player.sendSystemMessage(Component.literal("You can't manage this plot."));
            return;
        }

        Direction facing = serverLevel.getBlockState(payload.signPos()).getValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING);
        com.github.cerealklla.blueprynts.api.PlotArea plotArea = null;
        if (plot.constructionBoxId().isPresent() && ModList.get().isLoaded("blueprynts")) {
            plotArea = BlueprintsConstructionBridge.resolveStatus(serverLevel, plot.constructionBoxId().get())
                    .map(com.github.cerealklla.blueprynts.api.Blueprynts.ConstructionBoxStatus::plotArea)
                    .orElse(null);
        }

        BlockPos originalPos = payload.signPos();
        serverLevel.removeBlock(originalPos, false);
        com.github.cerealklla.settlemynts.plotsign.PlotConfigSignIndex.get(serverLevel.getServer()).remove(plot.plotId());
        com.github.cerealklla.settlemynts.plotsign.PendingPlotConfigSignRelocation.store(plot.plotId(), sign.settlementCoreId(), facing, plotArea, originalPos);
        ItemStack locator = com.github.cerealklla.settlemynts.plotsign.PlotConfigSignRelocatorItem.grantFor(plot.plotId(), serverLevel.getGameTime());
        if (!player.getInventory().add(locator)) {
            player.drop(locator, false);
        }
        player.sendSystemMessage(Component.literal("Plot Config Sign removed -- right-click a spot within the plot to place it."));
    }

    /**
     * "Get Plot Placement Stake" -- grants a "Plot Stakes" item bound to this settlement, with a
     * blank CurrentPlotID (Plot Stakes rework, 2026-09-30: the item is infinite-use and reusable
     * across many plots, not single-plot-bound, so there's no "active session" to resolve or reuse
     * here anymore -- see {@code PlotPlacementStakeItem}'s own doc).
     */
    private static void requestPlotStake(RequestPlotStakePayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)
                || !(player.level() instanceof ServerLevel serverLevel)
                || !(serverLevel.getEntity(payload.coreEntityId()) instanceof GhostTownHallCoreEntity core)
                || !core.isTownPlanner(player.getUUID())) {
            return;
        }
        ItemStack stake = new ItemStack(ModItems.PLOT_PLACEMENT_STAKE.get());
        stake.set(ModItems.PLOT_SESSION_DATA, new PlotSessionData(core.getUUID(), null));
        if (!player.getInventory().add(stake)) {
            player.drop(stake, false);
        }
    }

    /** "Get Roadway Stake" (Roadways Milestone 1, added 2026-10-06) -- mirrors {@link #requestPlotStake}. */
    private static void requestRoadwayStake(com.github.cerealklla.settlemynts.roadway.RequestRoadwayStakePayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)
                || !(player.level() instanceof ServerLevel serverLevel)
                || !(serverLevel.getEntity(payload.coreEntityId()) instanceof GhostTownHallCoreEntity core)
                || !core.isTownPlanner(player.getUUID())) {
            return;
        }
        ItemStack stake = new ItemStack(ModItems.ROADWAY_STAKE.get());
        stake.set(ModItems.ROADWAY_STAKE_OWNER_CORE_ID, core.getUUID());
        if (!player.getInventory().add(stake)) {
            player.drop(stake, false);
        }
    }

    /** "Show Roadway Stakes" toggle (Roadways Milestone 1) -- mirrors {@link #setShowPlotPerimeters}, but no wall regeneration needed: the stake entities themselves persist permanently, this only flips {@code broadcastToPlayer} visibility. */
    private static void setShowRoadwayStakes(com.github.cerealklla.settlemynts.roadway.SetShowRoadwayStakesPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        if (!(player.level() instanceof ServerLevel serverLevel) || !(serverLevel.getEntity(payload.coreEntityId()) instanceof GhostTownHallCoreEntity core)) {
            player.sendSystemMessage(Component.literal(
                    "Couldn't find this settlement's Town Hall Core anymore -- try right-clicking it again to reopen this screen."));
            return;
        }
        if (!core.isTownPlanner(player.getUUID())) {
            player.sendSystemMessage(Component.literal("You're not a Town Planner of this settlement."));
            return;
        }
        core.setShowRoadwayStakes(payload.visible());
    }

    /**
     * "Finalize Plot" -- computes the plot's real polygon and its "Town Proper" buffer from the
     * session's placed stakes (design doc Section 11a), registers both with Cartographyr, records
     * the result on the core, clears the session's stakes, and resets any Plot Stakes item still
     * carrying this plot's CurrentPlotID back to blank (see {@link #clearPlotStakeItems}).
     */
    /** Tier1=5/Tier2=10/Tier3=20/Tier4=35/Tier5=50 total plots (Town Hall included) -- explicit spec, 2026-10-09. */
    private static int maxPlotsForTownHallTier(int tier) {
        return switch (tier) {
            case 1 -> 5;
            case 2 -> 10;
            case 3 -> 20;
            case 4 -> 35;
            default -> 50;
        };
    }

    private static void finalizePlot(FinalizePlotPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        if (!(player.level() instanceof ServerLevel serverLevel)
                || !(serverLevel.getEntity(payload.stakeEntityId()) instanceof GhostPlotStakeEntity anchorStake)) {
            player.sendSystemMessage(Component.literal(
                    "Couldn't find that plot stake anymore -- try right-clicking one of its stakes again."));
            return;
        }
        UUID ownerCoreId = anchorStake.getOwnerCoreId();
        UUID plotSessionId = anchorStake.getPlotSessionId();
        if (!(serverLevel.getEntity(ownerCoreId) instanceof GhostTownHallCoreEntity core)) {
            player.sendSystemMessage(Component.literal("This plot's settlement is gone."));
            return;
        }
        if (!core.isTownPlanner(player.getUUID())) {
            player.sendSystemMessage(Component.literal("You're not a Town Planner of this settlement."));
            return;
        }
        if (payload.name().isBlank()) {
            player.sendSystemMessage(Component.literal("Set a plot name before finalizing."));
            return;
        }

        Identifier zoneTypeId = Identifier.parse(payload.zoneTypeId());
        Optional<ZoneType> zoneType = ZoneTypeRegistry.get(zoneTypeId);
        if (zoneType.isEmpty()) {
            player.sendSystemMessage(Component.literal("Unknown zone type -- try reopening the plot stake screen."));
            return;
        }

        List<GhostPlotStakeEntity> stakes = GhostPlotStakeEntity.findBySessionInPlacementOrder(serverLevel, ownerCoreId, plotSessionId);
        if (stakes.size() < 3) {
            player.sendSystemMessage(Component.literal("Place at least 3 plot stakes before finalizing."));
            return;
        }

        List<PlotGeometry.StakePoint> ordered = new ArrayList<>(stakes.size());
        for (GhostPlotStakeEntity stake : stakes) {
            ordered.add(new PlotGeometry.StakePoint(stake.getX(), stake.getZ()));
        }
        Geometry.Polygon plotPolygon = PlotGeometry.polygonFromStakes(ordered);

        // Hard-blocked, not just a warning -- a plot that can never fit a 15x15 Blueprynts
        // Construction Site can never receive the auto-placed Construction Box below, so there's no
        // valid state for it to finalize into. The live actionbar warning (see
        // sendPlotValidityFeedback, called on every stake add/remove) is meant to mean a Planner
        // never has to discover this by walking over and trying anyway.
        if (!PlotValidity.hasValidArea(plotPolygon)) {
            player.sendSystemMessage(Component.literal(
                    "This plot doesn't have a clear 15x15 area anywhere inside it yet -- add or adjust stakes before finalizing."));
            return;
        }

        // Redundant safety net, added 2026-09-30 per explicit user request: PlotPlacementStakeItem
        // already blocks placing a *new* stake inside an already-finalized plot, but two Town
        // Planners could still stake out overlapping plots concurrently (neither sees the other's
        // in-progress session) and both attempt to finalize -- the second one through must be
        // rejected here. core.getPlots() at this point still only holds the *other*, already
        // finalized plots -- this plot's own PlotRecord isn't added until core.addPlot(...) below.
        for (PlotRecord other : core.getPlots()) {
            Optional<GeographicEntity> otherEntity = Cartography.getEntity(serverLevel, new EntityId(other.cartographyrPlotEntityId()));
            if (otherEntity.isPresent() && otherEntity.get().geometry() instanceof Geometry.Polygon otherPolygon
                    && PlotGeometry.overlaps(plotPolygon, otherPolygon)) {
                player.sendSystemMessage(Component.literal(
                        "This plot overlaps plot \"" + other.name() + "\" -- adjust your stakes and try again."));
                return;
            }
        }

        // Added 2026-10-09, explicit request: "I'd like each tier to grant additional plots" -- caps
        // the settlement's TOTAL plot count (Town Hall included) by the Town Hall's own current Tier.
        // A settlement founding its very first plot (the Town Hall itself) has no Town Hall plot
        // record yet to read a Tier from -- it starts at Tier 1 like every other plot.
        boolean thisIsTownHall = zoneTypeId.equals(GhostTownHallCoreEntity.TOWN_HALL_ZONE_TYPE_ID);
        if (!thisIsTownHall && core.townHallPlot().isEmpty()) {
            player.sendSystemMessage(Component.literal("Found this settlement's Town Hall plot before any other Zone Type."));
            return;
        }
        int townHallTierForCap = thisIsTownHall ? 1 : core.townHallPlot().map(PlotRecord::tier).orElse(1);
        int maxPlots = maxPlotsForTownHallTier(townHallTierForCap);
        if (core.getPlots().size() >= maxPlots) {
            player.sendSystemMessage(Component.literal(
                    "This settlement is at its plot limit (" + maxPlots + ") for a Tier " + townHallTierForCap + " Town Hall -- upgrade the Town Hall to allow more plots."));
            return;
        }

        // Added 2026-10-09, explicit spec: "the town hall's tier itself allows a mayor to place those
        // zone types... a Tier 1 town can't have a Guardhouse at all" -- separate from (and checked
        // before) the Mayor-skill-level gate below.
        int minTownHallTier = com.github.cerealklla.settlemynts.zone.ZoneTypeUnlocks.minTownHallTierForZoneType(zoneTypeId);
        if (!thisIsTownHall && townHallTierForCap < minTownHallTier) {
            player.sendSystemMessage(Component.literal(
                    "This Zone Type needs a Tier " + minTownHallTier + " Town Hall (currently Tier " + townHallTierForCap + ")."));
            return;
        }

        // Added 2026-10-09, explicit spec: "the Mayor skill will be how certain plot types are
        // unlocked" -- gated against the settlement's own Mayor (founder), not whichever Town Planner
        // happens to be the one finalizing this particular plot, since the unlock is a settlement-wide
        // leadership concept.
        if (!thisIsTownHall && com.github.cerealklla.settlemynts.bridge.LyfeMayorBridge.isLoaded()) {
            int minMayorLevel = com.github.cerealklla.settlemynts.bridge.LyfeMayorBridge.minMayorLevelForZoneType(zoneTypeId);
            int founderMayorLevel = com.github.cerealklla.settlemynts.bridge.LyfeMayorBridge.getMayorLevel(serverLevel.getServer(), core.getFounderId());
            if (founderMayorLevel < minMayorLevel) {
                player.sendSystemMessage(Component.literal(
                        "This Zone Type needs the settlement's Mayor to reach Mayor level " + minMayorLevel + " (currently level " + founderMayorLevel + ")."));
                return;
            }
        }

        // Design doc Section 10: "Finalize... is only clickable once... a roadway flag has been
        // placed" -- hard-blocked, same reasoning as the area check above.
        GhostRoadAccessFlagEntity roadFlag = GhostRoadAccessFlagEntity.findBySession(serverLevel, ownerCoreId, plotSessionId);
        if (roadFlag == null) {
            player.sendSystemMessage(Component.literal(
                    "Place the Road Access Flag on the plot's perimeter before finalizing."));
            return;
        }
        BlockPos roadFlagPos = new BlockPos((int) Math.floor(roadFlag.getX()), (int) Math.floor(roadFlag.getY()), (int) Math.floor(roadFlag.getZ()));

        Geometry.Polygon bufferPolygon = PlotGeometry.paddedBuffer(plotPolygon, PLOT_BUFFER_PADDING_BLOCKS);

        GeographicEntity plotEntity = Cartography.createEntity(serverLevel, new EntityDefinition(
                serverLevel.dimension(), Classification.CONSTRUCTED, PLOT_ENTITY_TYPE, ZONE_LAYER_ID,
                Optional.of(payload.name()), plotPolygon, LifecycleState.REALIZED, Optional.empty()));
        Cartography.setDesignation(serverLevel, plotEntity.id(), zoneType.get().label());

        // No name/designation on the buffer entity -- it's only ever meant to resolve to "Town
        // Proper" (design doc Section 11a), never a specific plot's own zone/name.
        GeographicEntity bufferEntity = Cartography.createEntity(serverLevel, new EntityDefinition(
                serverLevel.dimension(), Classification.CONSTRUCTED, PLOT_BUFFER_ENTITY_TYPE, ZONE_LAYER_ID,
                Optional.empty(), bufferPolygon, LifecycleState.REALIZED, Optional.empty()));

        // Blueprynts-free site finding (extracted 2026-09-30, see PlotSitePlacement's own doc) --
        // computed once regardless of whether Blueprynts is loaded, so the Plot Config Sign below
        // always has somewhere sensible to anchor next to, even on a server with no Building Supply
        // Box (and so no structure at all) for this plot.
        com.github.cerealklla.settlemynts.zone.PlotSitePlacement.Site site =
                com.github.cerealklla.settlemynts.zone.PlotSitePlacement.find(serverLevel, plotPolygon, roadFlagPos);

        // New optional dependency direction (2026-09-29): the reverse of Blueprynts' own existing
        // optional dependency on Settlemynts. Guarded so a Blueprynts-less server never force-loads
        // its classes -- see bridge.BlueprintsConstructionBridge's own doc.
        Optional<BlockPos> boxPos = Optional.empty();
        Optional<UUID> constructionBoxId = Optional.empty();
        if (site != null && ModList.get().isLoaded("blueprynts")) {
            BlueprintsConstructionBridge.Placement placement = BlueprintsConstructionBridge.placeConstructionBox(serverLevel, site, zoneTypeId);
            if (placement != null) {
                boxPos = Optional.of(placement.pos());
                constructionBoxId = Optional.of(placement.constructionId());
            }
        }

        // Design doc Section 10: "Owner -- a player name, or an NPC (defaults to NPC)" -- added
        // 2026-09-30, closing the gap this whole flow previously left flagged. Resolved the same way
        // GrantTownPlannerPayload's own handler already resolves a typed player name (getPlayerByName
        // -- online players only, a known v1 limitation shared with that handler, not a new one) --
        // a blank or unresolvable name both fall back to NPC-owned rather than hard-blocking Finalize,
        // since an owner is never a required field per the design doc.
        Optional<UUID> owner = Optional.empty();
        if (!payload.ownerName().isBlank()) {
            if (zoneType.get().npcOwnedOnly()) {
                player.sendSystemMessage(Component.literal(
                        zoneType.get().label() + " plots are always NPC-owned -- ignoring the Owner field."));
            } else {
                ServerPlayer ownerPlayer = serverLevel.getServer().getPlayerList().getPlayerByName(payload.ownerName());
                if (ownerPlayer != null) {
                    owner = Optional.of(ownerPlayer.getUUID());
                } else {
                    player.sendSystemMessage(Component.literal(
                            "No online player named \"" + payload.ownerName() + "\" (assigning an offline owner isn't supported yet) -- plot will be NPC-owned instead."));
                }
            }
        }

        // Recurring rent bill (2026-10-05, see decisions.md) -- player-owned plots only ("NPC Owned
        // plots will not have a recurring rent," explicit user decision). Registered with empty box
        // lists; both the plot's own source box and the settlement's Town Hall destination box(es)
        // are discovered live and kept in sync by bills.PlotRentTicker, not resolved here -- neither
        // is guaranteed to exist yet at Finalize time (both are player-placed, whenever that happens).
        Optional<UUID> billId = Optional.empty();
        if (owner.isPresent() && YconomicsBillBridge.isAvailable()) {
            billId = Optional.of(YconomicsBillBridge.registerPlotBill(serverLevel, plotSessionId, List.of(), List.of(),
                    Map.of(Identifier.withDefaultNamespace("gold_nugget"), 1), 1L));
        }

        PlotRecord plotRecord = new PlotRecord(plotSessionId, payload.name(), zoneTypeId, plotEntity.id().value(), bufferEntity.id().value(), boxPos, constructionBoxId, owner, billId, Optional.empty(), java.util.List.of(), 1, java.util.List.of());
        core.addPlot(plotRecord);

        // Plot Config Sign (design doc Section 14a) -- spawned unconditionally, next to the same
        // site the Building Supply Box anchors to (not on top of it), regardless of whether
        // Blueprynts is loaded/a box was actually placed -- see PlotConfigSignSpawner's own doc.
        if (site != null) {
            com.github.cerealklla.settlemynts.plotsign.PlotConfigSignSpawner.spawn(serverLevel, core.getUUID(), plotSessionId, site);
            // Shop auto-seeding (design doc Section 14a, 2026-10-05) -- eager, Tier-1-only (a
            // Construction Box's own Tier isn't chosen until after Finalize); see ShopSeeding's own
            // doc for the idempotent Tier-catch-up that happens later on every Shop-menu open.
            com.github.cerealklla.settlemynts.zone.ShopSeeding.seedNewPlot(serverLevel, plotRecord, core, site.pos());
        }

        // Guardhouse Plot Type (design doc Section 14a) -- its own structure, only for a
        // Guardhouse-typed plot, spawned alongside the Plot Config Sign above.
        if (site != null && zoneTypeId.equals(com.github.cerealklla.settlemynts.guardhouse.GuardhouseConstants.GUARDHOUSE_ZONE_TYPE_ID)) {
            com.github.cerealklla.settlemynts.guardhouse.GuardhouseSpawner.spawn(serverLevel, core.getUUID(), plotSessionId, site);
        }

        // Optional dependency, 2026-09-29 (design discussion): Cartographyr's own registration above
        // is purely spatial -- it has no permission concept. Protectyons owns "who can edit blocks
        // here," registered separately with the same polygon and the plotSessionId as the shared area
        // id. Permitted players computed via PlotPermissions#computeProtectionPermittedPlayers
        // (reworked 2026-10-05): Mayor always, Town Planners only on non-privately-owned plots, plus
        // this plot's own assigned Owner -- see that method's own doc and ProtectyonsPlotBridge's.
        if (ModList.get().isLoaded("protectyons")) {
            Set<UUID> permitted = PlotPermissions.computeProtectionPermittedPlayers(plotRecord, core);
            ProtectyonsPlotBridge.registerPlot(serverLevel, plotSessionId, plotPolygon, permitted);
        }
        for (GhostPlotStakeEntity stake : stakes) {
            stake.discard();
        }
        roadFlag.discard();
        RopeFenceLeash.clearGhostAnchor(player.getUUID());
        GhostPlotFencePostEntity.regenerateCarryPreview(serverLevel, ownerCoreId, plotSessionId, null, null); // Clears any leftover carry preview.
        int cleared = clearPlotStakeItems(serverLevel, core, plotSessionId);

        player.sendSystemMessage(Component.literal(
                "Plot \"" + payload.name() + "\" (" + zoneType.get().label() + ") finalized and registered with Cartographyr"
                        + (cleared > 0 ? " -- reset " + cleared + " Plot Stakes/Road Access Flag item(s) back to unbound." : ".")
                        + (boxPos.isPresent() ? " A Construction Box has been placed for it." : "")));
    }


    /**
     * Mirrors {@link #clearPerimeterStakeItems} in spirit, but no longer deletes anything (Plot
     * Stakes rework, 2026-09-30, extended to the Road Access Flag the same day) -- both items are
     * reusable, infinite-use tools now, not single-plot-bound one-shots, so finalizing a plot just
     * resets any stack still carrying this specific finished plot's CurrentPlotID back to blank
     * (ready to start/resume or bind to a different plot), the same reset a hand-swap already does.
     * Deliberately doesn't filter by item type -- both {@code PLOT_PLACEMENT_STAKE} and {@code
     * ROAD_ACCESS_FLAG} carry the same {@code PLOT_SESSION_DATA} component, and any stack carrying
     * it that matches this plot's id should be reset regardless of which of the two it is.
     */
    private static int clearPlotStakeItems(ServerLevel level, GhostTownHallCoreEntity core, UUID plotSessionId) {
        int cleared = 0;
        for (UUID plannerId : core.getTownPlanners()) {
            ServerPlayer planner = level.getServer().getPlayerList().getPlayer(plannerId);
            if (planner == null) {
                continue;
            }
            var inventory = planner.getInventory();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                ItemStack stack = inventory.getItem(slot);
                PlotSessionData session = stack.get(ModItems.PLOT_SESSION_DATA);
                if (session != null && plotSessionId.equals(session.plotSessionId())) {
                    stack.set(ModItems.PLOT_SESSION_DATA, new PlotSessionData(session.ownerCoreId(), null));
                    cleared++;
                }
            }
        }
        return cleared;
    }

    /**
     * "Show Plot Perimeters" toggle -- mirrors {@link #setBoundaryVisible} but draws every
     * finalized plot's *real* polygon (not the buffer), colorized per its own zone type, sourced
     * fresh from Cartographyr each time rather than cached locally.
     */
    private static void setShowPlotPerimeters(SetShowPlotPerimetersPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        if (!(player.level() instanceof ServerLevel serverLevel) || !(serverLevel.getEntity(payload.coreEntityId()) instanceof GhostTownHallCoreEntity core)) {
            player.sendSystemMessage(Component.literal(
                    "Couldn't find this settlement's Town Hall Core anymore -- try right-clicking it again to reopen this screen."));
            return;
        }
        if (!core.isTownPlanner(player.getUUID())) {
            player.sendSystemMessage(Component.literal("You're not a Town Planner of this settlement."));
            return;
        }

        for (GhostPlotWallEntity existing : GhostPlotWallEntity.findByOwnerCore(serverLevel, core.getUUID())) {
            existing.discard();
        }

        core.setShowPlotPerimeters(payload.visible());
        if (!payload.visible()) {
            return;
        }

        for (PlotRecord plot : core.getPlots()) {
            Optional<GeographicEntity> plotEntity = Cartography.getEntity(serverLevel, new EntityId(plot.cartographyrPlotEntityId()));
            if (plotEntity.isEmpty() || !(plotEntity.get().geometry() instanceof Geometry.Polygon polygon)) {
                continue;
            }
            ZoneType zoneType = ZoneTypeRegistry.get(plot.zoneTypeId()).orElse(null);
            if (zoneType == null) {
                continue;
            }

            for (Geometry.Polygon.Vertex block : Geometry.Polygon.outerRing(polygon)) {
                // MOTION_BLOCKING_NO_LEAVES, not WORLD_SURFACE -- see PlotSitePlacement's own comment;
                // a wall point under a tree canopy used to start floating up in the leaves instead of
                // on the real ground (2026-09-30 playtest report: "they should build from the ground
                // up... they can be inside other blocks").
                int groundY = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, block.x(), block.z());
                for (int level = 0; level < GhostPlotWallEntity.WALL_HEIGHT_BLOCKS; level++) {
                    // No +0.5 centering here (fixed 2026-09-27, playtest feedback: the glass wasn't
                    // stacked on the block below it, offset by half a block) -- a Display.BlockDisplay
                    // renders its block model anchored at the entity's own position as that block's own
                    // low corner, the same convention a normally placed block uses, not the "centered
                    // icon" convention the old floating-item entities needed.
                    GhostPlotWallEntity.create(serverLevel, block.x(), groundY + level, block.z(), core.getUUID(), zoneType.wallBlock().defaultBlockState());
                }
            }
        }
    }

    /**
     * "Reposition Town Hall Core" (added 2026-09-30) -- grants a Locator bound to {@code core}'s own
     * persistent UUID once it's confirmed the Core actually sits inside a finalized Town Hall plot
     * right now; the granted item re-validates this fresh at use time too (see {@code
     * founding.TownHallCoreRelocatorItem}), so nothing here needs to be cached.
     */
    private static void repositionTownHallCore(com.github.cerealklla.settlemynts.founding.RepositionTownHallCorePayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        if (!(player.level() instanceof ServerLevel serverLevel) || !(serverLevel.getEntity(payload.coreEntityId()) instanceof GhostTownHallCoreEntity core)) {
            player.sendSystemMessage(Component.literal(
                    "Couldn't find this settlement's Town Hall Core anymore -- try right-clicking it again to reopen this screen."));
            return;
        }
        if (!core.isTownPlanner(player.getUUID())) {
            player.sendSystemMessage(Component.literal("You're not a Town Planner of this settlement."));
            return;
        }
        if (core.findTownHallPlot(serverLevel).isEmpty()) {
            player.sendSystemMessage(Component.literal("The Town Hall Core isn't currently inside a finalized Town Hall plot."));
            return;
        }
        player.addItem(com.github.cerealklla.settlemynts.founding.TownHallCoreRelocatorItem.grantFor(core.getUUID(), serverLevel.getGameTime()));
        player.sendSystemMessage(Component.literal("Right-click anywhere within the Town Hall Plot to move the Core there."));
    }

    /**
     * Toggles "View Settlement Boundaries" (design doc Section 9). Turning it on discards any
     * existing wall (in case one was left over from a stale state) and generates a fresh one; turning
     * it off just discards every wall point for this core.
     *
     * <p>The wall itself marks the first NOT-permitted ring, same meaning as the plot wall (2026-09-27,
     * explicit user request to make this consistent) -- traced via {@link Geometry.Polygon#outerRing}
     * one block outside the settlement's real, block-inclusive core boundary ({@link
     * Geometry.Polygon#coveringBlocks}).
     *
     * <p><b>Source of the core boundary depends on whether this settlement has been finalized</b>
     * (fixed 2026-09-27, live playtest: this toggle broke entirely post-Finalize once {@code
     * finalizeSettlement} started discarding the perimeter stake entities, same date -- see
     * decisions.md). Before Finalize, no Cartographyr entity exists yet, so this builds the polygon
     * live from the current stakes (in **placement order**, not angularly sorted -- required for
     * non-star-shaped perimeters to build a valid polygon at all). After Finalize, the stakes are
     * gone, so this reads the already-registered core polygon back from Cartographyr instead -- the
     * same pattern {@code setShowPlotPerimeters} already uses for plots.
     */
    private static void setBoundaryVisible(SetBoundaryVisiblePayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        if (!(player.level() instanceof ServerLevel serverLevel) || !(serverLevel.getEntity(payload.coreEntityId()) instanceof GhostTownHallCoreEntity core)) {
            player.sendSystemMessage(Component.literal(
                    "Couldn't find this settlement's Town Hall Core anymore -- try right-clicking it again to reopen this screen."));
            return;
        }
        if (!core.isTownPlanner(player.getUUID())) {
            player.sendSystemMessage(Component.literal("You're not a Town Planner of this settlement."));
            return;
        }

        for (GhostBoundaryWallEntity existing : GhostBoundaryWallEntity.findByOwnerCore(serverLevel, core.getUUID())) {
            existing.discard();
        }

        if (!payload.visible()) {
            core.setBoundaryVisible(false);
            return;
        }

        // Post-Finalize, the perimeter stake entities themselves are gone (finalizeSettlement
        // discards them, 2026-09-27, matching what finalizePlot already did) -- source the wall from
        // the already-registered Cartographyr core polygon instead, same pattern
        // setShowPlotPerimeters already uses for plots. Pre-Finalize, no core polygon exists yet, so
        // this still has to build one live from the current stakes.
        Geometry.Polygon corePolygon;
        Long coreEntityId = core.getCartographyrCoreEntityId();
        if (coreEntityId != null) {
            Optional<GeographicEntity> registered = Cartography.getEntity(serverLevel, new EntityId(coreEntityId));
            if (registered.isEmpty() || !(registered.get().geometry() instanceof Geometry.Polygon registeredPolygon)) {
                player.sendSystemMessage(Component.literal("This settlement's registered boundary is missing -- try finalizing again."));
                return;
            }
            corePolygon = registeredPolygon;
        } else {
            List<GhostPerimeterStakeEntity> stakes = GhostPerimeterStakeEntity.findByOwnerCoreInPlacementOrder(serverLevel, core.getUUID());
            if (stakes.size() < 3) {
                player.sendSystemMessage(Component.literal("Place at least 3 perimeter stakes before showing the boundary."));
                return;
            }
            List<Geometry.Polygon.Vertex> coreVertices = new ArrayList<>(stakes.size());
            for (GhostPerimeterStakeEntity stake : stakes) {
                coreVertices.add(new Geometry.Polygon.Vertex((int) Math.floor(stake.getX()), (int) Math.floor(stake.getZ())));
            }
            corePolygon = Geometry.Polygon.coveringBlocks(coreVertices);
        }

        for (Geometry.Polygon.Vertex block : Geometry.Polygon.outerRing(corePolygon)) {
            // Anchored to this point's own local ground height, not the core's fixed Y (fixed
            // 2026-09-26, playtest feedback -- varying terrain along the perimeter made single
            // fixed-height blocks read as scattered floating icons, not a wall). A short vertical
            // stack per point gives a real "wall" silhouette even where the ground itself slopes.
            // MOTION_BLOCKING_NO_LEAVES, not WORLD_SURFACE -- same fix as setShowPlotPerimeters above.
            int groundY = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, block.x(), block.z());
            for (int level = 0; level < GhostBoundaryWallEntity.WALL_HEIGHT_BLOCKS; level++) {
                // No +0.5 centering -- see the identical fix/comment in setShowPlotPerimeters, same date.
                GhostBoundaryWallEntity.create(serverLevel, block.x(), groundY + level, block.z(), core.getUUID());
            }
        }
        core.setBoundaryVisible(true);
    }

    // Design doc Section 8: "10 feet larger in every direction" -- corrected to blocks in the
    // design doc itself (2026-09-26, see decisions.md): the user's real intent was 10 blocks
    // (~33 feet), not 10 literal feet (~3 blocks, too thin to read as a real buffer).
    private static final double CARTOGRAPHYR_PADDING_BLOCKS = 10.0;

    /**
     * Runs the perimeter auto-fit (design doc Section 7, {@link PerimeterFit}), repositions the
     * stakes, registers (or updates) the settlement's polygon with Cartographyr (design doc Section
     * 8 -- a 10-block padded buffer around the fitted perimeter, "a no man's zone"), and clears any
     * leftover Planned Perimeter Stake items from the settlement's online Town Planners (no longer
     * needed once staking is done). **Does not yet solidify the stakes/core into real blocks or
     * enable protection** -- the rest of Section 8, a later milestone.
     */
    private static void finalizeSettlement(FinalizeSettlementPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return; // No player to message -- genuinely nothing to do.
        }
        if (!(player.level() instanceof ServerLevel serverLevel) || !(serverLevel.getEntity(payload.coreEntityId()) instanceof GhostTownHallCoreEntity core)) {
            // Every other branch below can explain itself in a chat message; this one can't --
            // the core entity this screen was opened for is gone (a stale network id from an
            // entity reload, or the core was somehow removed). Still message rather than fail
            // silently, so a report like "Finalize does nothing" is actually diagnosable.
            player.sendSystemMessage(Component.literal(
                    "Couldn't find this settlement's Town Hall Core anymore -- try right-clicking it again to reopen this screen."));
            return;
        }
        if (!core.isTownPlanner(player.getUUID())) {
            player.sendSystemMessage(Component.literal("You're not a Town Planner of this settlement."));
            return;
        }
        if (core.getSettlementName().isBlank()) {
            player.sendSystemMessage(Component.literal("Set a settlement name before finalizing."));
            return;
        }

        List<GhostPerimeterStakeEntity> stakes = GhostPerimeterStakeEntity.findByOwnerCoreInPlacementOrder(serverLevel, core.getUUID());
        if (stakes.size() < 3) {
            player.sendSystemMessage(Component.literal("Place at least 3 perimeter stakes before finalizing."));
            return;
        }

        List<PerimeterFit.StakeInput> inputs = new ArrayList<>(stakes.size());
        for (GhostPerimeterStakeEntity stake : stakes) {
            inputs.add(new PerimeterFit.StakeInput(stake.getX(), stake.getZ(), stake.isAbsolute()));
        }

        // The core entity is always created at a block's CENTER (sitePos + 0.5, see
        // SettlementClaimFlagItem), but stakes sit at a block's CORNER (no +0.5, see
        // PlannedPerimeterStakeItem's 2026-09-27 fix) -- the two conventions don't match. Scaling
        // radially from the core's raw (center) coordinate against corner-coordinate stakes silently
        // introduces up to a 0.5-block pivot error, which the fit's own scale factor then amplifies
        // (a real playtest bug, 2026-09-27: a small symmetric diamond finalized into a wildly
        // lopsided square once scaled ~100x to hit the target area). PlannedPerimeterStakeItem's own
        // distance check already converts the other direction (adding 0.5 to a stake's corner
        // position) for exactly this reason -- this converts the core's center position down to the
        // matching corner coordinate instead, so both the containment check and the fit itself pivot
        // around the same point the stakes are actually measured in.
        double coreCornerX = core.getX() - 0.5;
        double coreCornerZ = core.getZ() - 0.5;

        if (!PerimeterFit.containsPoint(inputs, coreCornerX, coreCornerZ)) {
            player.sendSystemMessage(Component.literal(
                    "The perimeter stakes don't fully encompass the Town Hall Core yet."));
            return;
        }

        PerimeterFit.FitResult result = PerimeterFit.fit(
                coreCornerX, coreCornerZ, inputs, PerimeterFit.DEFAULT_TARGET_AREA_BLOCKS, PerimeterFit.DEFAULT_MAX_FIT_RADIUS_BLOCKS);

        for (int i = 0; i < stakes.size(); i++) {
            PerimeterFit.StakeInput fitted = result.fittedStakes().get(i);
            GhostPerimeterStakeEntity stake = stakes.get(i);
            stake.setPos(fitted.x(), stake.getY(), fitted.z());
        }

        registerWithCartographyr(serverLevel, core, result.fittedStakes());
        int stakesCleared = clearPerimeterStakeItems(serverLevel, core);
        core.setFinalized(true);
        core.setActivePerimeterPlanner(null); // Nothing left in-progress -- release the lock (design doc Section 7a; see decisions.md 2026-09-27).
        // The stake entities themselves are discarded too (2026-09-27, explicit user request,
        // matching what finalizePlot already did for plot stakes) -- the fitted shape is already
        // baked into the registered Cartographyr polygon at this point, so nothing still needs them.
        // Re-opening perimeter staking later (a fresh RequestPerimeterStakePayload) starts clean,
        // same as founding a settlement in the first place.
        for (GhostPerimeterStakeEntity stake : stakes) {
            stake.discard();
        }
        for (GhostPerimeterFencePostEntity post : GhostPerimeterFencePostEntity.findByOwnerCore(serverLevel, core.getUUID())) {
            post.discard(); // The preview's job is done -- the real wall/polygon now exists.
        }

        player.sendSystemMessage(Component.literal(
                "Perimeter fitted to " + Math.round(result.achievedArea()) + " blocks^2 (target "
                        + Math.round(PerimeterFit.DEFAULT_TARGET_AREA_BLOCKS) + "). Registered with Cartographyr"
                        + (stakesCleared > 0 ? " -- cleared " + stakesCleared + " leftover Perimeter Stake item(s)." : ".")
                        + " Solidifying the core/stakes and enabling protection isn't implemented yet."));
    }

    /**
     * Registers this settlement's fitted perimeter with Cartographyr as a {@code
     * Classification.CONSTRUCTED}/{@code EntityType.SETTLEMENT} entity -- design doc Section 8. The
     * polygon sent is the real (unpadded) fitted polygon grown outward by {@link
     * #CARTOGRAPHYR_PADDING_BLOCKS} via Cartographyr's {@code Geometry.Polygon#expandedBy}, which
     * follows the settlement's own shape rather than cutting across a concave notch (fixed
     * 2026-09-27, replacing a radial-scale-from-core version -- see {@code
     * zone.PlotGeometry#paddedBuffer}'s own doc for the identical bug/fix on the plot side).
     * Idempotent: a settlement already registered (tracked via {@code
     * GhostTownHallCoreEntity#getCartographyrEntityId}) gets its existing entity's geometry
     * *updated* instead of a duplicate being created, so re-running Finalize later (e.g. after
     * adjusting stakes) doesn't leave stale entries behind.
     */
    // 2026-10-05 fix (real report: 21 placed stakes produced a 486-vertex registered polygon,
    // enough to visibly lag Lyfe's minimap): coveringBlocks/expandedBy trace a diagonal edge as a
    // one-block staircase, not a straight line -- see Geometry.Polygon#simplified's own doc for why
    // that's fixed with an explicit, opt-in simplification here rather than inside Cartographyr's
    // exact block-level primitives themselves (Plot subdivision needs those to stay exact).
    private static final double PERIMETER_SIMPLIFY_TOLERANCE_BLOCKS = 1.5;

    private static void registerWithCartographyr(ServerLevel level, GhostTownHallCoreEntity core, List<PerimeterFit.StakeInput> fittedStakes) {
        // fittedStakes is already in placement order (PerimeterFit.fit preserves whatever order its
        // caller passed in, 2026-09-27 -- see decisions.md same date) -- no re-sort needed here.
        List<Geometry.Polygon.Vertex> realVertices = new ArrayList<>(fittedStakes.size());
        for (PerimeterFit.StakeInput stake : fittedStakes) {
            realVertices.add(new Geometry.Polygon.Vertex((int) Math.floor(stake.x()), (int) Math.floor(stake.z())));
        }
        Geometry.Polygon realPolygon = Geometry.Polygon.coveringBlocks(realVertices).simplified(PERIMETER_SIMPLIFY_TOLERANCE_BLOCKS);
        registerSettlementCore(level, core, realPolygon);

        Geometry.Polygon paddedUnsimplified = Geometry.Polygon.expandedBy(realPolygon, (int) Math.round(CARTOGRAPHYR_PADDING_BLOCKS));
        Geometry paddedPolygon = paddedUnsimplified.simplified(PERIMETER_SIMPLIFY_TOLERANCE_BLOCKS);

        // 2026-10-05 fix: the settlement area itself must also be registered with Protectyons, not
        // just its plots -- see ProtectyonsPlotBridge#registerSettlement's own doc for the live-test
        // bug this closes (founder blocked from breaking terrain in their own freshly-finalized
        // settlement, since nothing had ever granted a permitted set for the settlement-wide area).
        if (ModList.get().isLoaded("protectyons")) {
            ProtectyonsPlotBridge.registerSettlement(level, core.getUUID(), paddedPolygon, core.getTownPlanners());
        }

        Long existingId = core.getCartographyrEntityId();
        if (existingId != null) {
            // The common case now: every settlement is already registered at LifecycleState.PLANNED
            // from the moment it was founded (see SettlementClaimFlagItem) specifically so the
            // founding-distance check has a chunk-load-independent record of it even before this
            // point is ever reached. Finalize is what promotes it to REALIZED, fills in the real
            // name (blank at founding time), and swaps in the real fitted/padded polygon.
            Optional<GeographicEntity> updated = Cartography.updateEntity(level, new EntityId(existingId), e -> e
                    .withGeometry(paddedPolygon)
                    .withName(Optional.of(core.getSettlementName()))
                    .withLifecycleState(LifecycleState.REALIZED));
            if (updated.isPresent()) {
                return;
            }
            // The tracked id no longer resolves to a real entity (shouldn't normally happen) --
            // fall through and register fresh rather than leaving this settlement unregistered.
        }

        GeographicEntity created = Cartography.createEntity(level, new EntityDefinition(
                level.dimension(),
                Classification.CONSTRUCTED,
                EntityType.SETTLEMENT,
                Layer.SETTLEMENT_ID,
                Optional.of(core.getSettlementName()),
                paddedPolygon,
                LifecycleState.REALIZED,
                Optional.empty()));
        core.setCartographyrEntityId(created.id().value());
    }

    /**
     * Registers/updates the settlement's *real* (unpadded) fitted polygon as a separate {@link
     * #SETTLEMENT_CORE_ENTITY_TYPE} entity, purely so Lyfe's HUD can tell "genuinely inside the
     * built town" (this entity) apart from "inside the settlement's outer padding buffer only"
     * (the existing padded `EntityType.SETTLEMENT` entity, unaffected by this method). Mirrors
     * {@link #registerWithCartographyr}'s own idempotent update-or-create shape.
     */
    private static void registerSettlementCore(ServerLevel level, GhostTownHallCoreEntity core, Geometry realPolygon) {
        Long existingId = core.getCartographyrCoreEntityId();
        if (existingId != null) {
            Optional<GeographicEntity> updated = Cartography.updateEntity(level, new EntityId(existingId), e -> e
                    .withGeometry(realPolygon)
                    .withName(Optional.of(core.getSettlementName()))
                    .withLifecycleState(LifecycleState.REALIZED));
            if (updated.isPresent()) {
                return;
            }
        }
        GeographicEntity created = Cartography.createEntity(level, new EntityDefinition(
                level.dimension(),
                Classification.CONSTRUCTED,
                SETTLEMENT_CORE_ENTITY_TYPE,
                Layer.SETTLEMENT_ID,
                Optional.of(core.getSettlementName()),
                realPolygon,
                LifecycleState.REALIZED,
                Optional.empty()));
        core.setCartographyrCoreEntityId(created.id().value());
    }

    /** Removes every Planned Perimeter Stake item from each of the settlement's currently-online Town Planners' inventories -- no longer needed once staking is done. Offline planners are left alone (nothing to touch); returns the total number of item stacks removed, for the confirmation message. */
    private static int clearPerimeterStakeItems(ServerLevel level, GhostTownHallCoreEntity core) {
        int cleared = 0;
        for (UUID plannerId : core.getTownPlanners()) {
            ServerPlayer planner = level.getServer().getPlayerList().getPlayer(plannerId);
            if (planner == null) {
                continue;
            }
            var inventory = planner.getInventory();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                if (inventory.getItem(slot).is(ModItems.PLANNED_PERIMETER_STAKE.get())) {
                    inventory.setItem(slot, ItemStack.EMPTY);
                    cleared++;
                }
            }
        }
        return cleared;
    }

    private static GhostTownHallCoreEntity ownerCoreOf(ServerLevel level, GhostPerimeterStakeEntity stake) {
        if (stake.getOwnerCoreId() == null) {
            return null;
        }
        Entity entity = level.getEntity(stake.getOwnerCoreId());
        return entity instanceof GhostTownHallCoreEntity core ? core : null;
    }

    private static final int ROAD_ACCESS_PREVIEW_INTERVAL_TICKS = 10;

    /**
     * Live "where can the Road Access Flag go" ghost-torch preview (explicit user request,
     * 2026-09-29) -- every {@link #ROAD_ACCESS_PREVIEW_INTERVAL_TICKS} ticks, regenerates {@code
     * GhostRoadAccessPreviewEntity} markers over every currently-valid perimeter cell for any online
     * player holding a Road Access Flag item, and clears them for anyone no longer holding it -- tied
     * to the item actually being in hand, not just an active plot session, per the user's own framing
     * ("as we're walking around with a road stake in hand").
     *
     * <p>Keeps showing the preview even once a flag is already placed for the session -- a real
     * inconsistency, 2026-09-29: the item is never consumed on placement (design doc: "placing a new
     * one replaces the old," the same reusable-item shape {@code PlotPlacementStakeItem} already
     * uses), so hiding the preview the instant one exists left the player still holding the item with
     * no visual feedback at all, confusing on its own terms ("placing the road stake down should
     * remove it from your inventory, or keep the ghost torches visible, one of the two"). Since the
     * item staying reusable is the existing, intended design, the preview now matches that instead.
     */
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % ROAD_ACCESS_PREVIEW_INTERVAL_TICKS != 0) {
            return;
        }
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            if (!(player.level() instanceof ServerLevel serverLevel)) {
                continue;
            }
            regenerateCarryPreview(player, serverLevel);
            sendAnchorDistance(player, serverLevel);
            PlotSessionData session = resolveRoadAccessFlagSession(player);
            // Plot Stakes rework (2026-09-30) made PlotSessionData#plotSessionId nullable -- a
            // real server crash confirmed a Road Access Flag item can carry one with a null
            // plotSessionId (an older/leftover flag stack), which used to NPE straight through
            // findBySessionInPlacementOrder below. Treated the same as "not holding it."
            if (session == null || session.plotSessionId() == null) {
                // Not holding the item (switched away, dropped it, removed from hotbar, etc.) -- a
                // real bug, 2026-09-29: this used to just `continue`, leaving any already-spawned
                // torches for this player floating forever, since nothing else ever cleared them.
                GhostRoadAccessPreviewEntity.regenerate(serverLevel, player.getUUID(), List.of());
                continue;
            }
            UUID ownerCoreId = session.ownerCoreId();
            UUID plotSessionId = session.plotSessionId();
            List<GhostPlotStakeEntity> stakes = GhostPlotStakeEntity.findBySessionInPlacementOrder(serverLevel, ownerCoreId, plotSessionId);
            if (stakes.size() < 3) {
                GhostRoadAccessPreviewEntity.regenerate(serverLevel, player.getUUID(), List.of());
                continue;
            }
            List<PlotGeometry.StakePoint> ordered = new ArrayList<>(stakes.size());
            for (GhostPlotStakeEntity stake : stakes) {
                ordered.add(new PlotGeometry.StakePoint(stake.getX(), stake.getZ()));
            }
            Geometry.Polygon plotPolygon = PlotGeometry.polygonFromStakes(ordered);
            GhostRoadAccessPreviewEntity.regenerate(serverLevel, player.getUUID(), PlotGeometry.perimeterCells(plotPolygon));
        }
    }

    /**
     * Rope Fence rework (see decisions.md) -- redraws the single live "carrying" rope trailing from
     * a player's current ghost leash anchor to their own live position, piggybacking on the same
     * 10-tick cadence as the Road Access Flag preview above (cheap either way -- at most 5 cells).
     * A player with no active anchor (not holding a Plot Placement Stake, or never placed a post
     * yet) just gets the preview cleared.
     */
    private static void regenerateCarryPreview(ServerPlayer player, ServerLevel serverLevel) {
        GhostPlotStakeEntity anchor = player.getMainHandItem().is(ModItems.PLOT_PLACEMENT_STAKE.get())
                ? RopeFenceLeash.resolveGhostAnchor(serverLevel, player.getUUID())
                : null;
        if (anchor == null) {
            return; // Nothing to clear per-player -- regenerateCarryPreview(level, core, session, null, null) at removal/finalize already handles that case.
        }
        GhostPlotFencePostEntity.regenerateCarryPreview(serverLevel, anchor.getOwnerCoreId(), anchor.getPlotSessionId(), anchor, player.position());
    }

    /**
     * Drives {@code client.StakeDistanceOverlay}'s "Distance from Stake" readout for Plot Placement
     * Stakes and Roadway Stakes (added 2026-10-09, explicit request: the same live readout Perimeter
     * Stakes already have from the Town Hall, but from the currently-attached anchor stake instead).
     * Piggybacks on the same 10-tick cadence as the carry-preview/Road-Access-Flag work above -- the
     * anchor itself never moves once placed, so this only needs to be "fresh enough," not per-frame.
     */
    private static void sendAnchorDistance(ServerPlayer player, ServerLevel serverLevel) {
        net.minecraft.core.BlockPos anchorPos = null;
        if (player.getMainHandItem().is(ModItems.PLOT_PLACEMENT_STAKE.get())) {
            GhostPlotStakeEntity anchor = RopeFenceLeash.resolveGhostAnchor(serverLevel, player.getUUID());
            if (anchor != null) {
                anchorPos = net.minecraft.core.BlockPos.containing(anchor.getX(), anchor.getY(), anchor.getZ());
            }
        } else if (player.getMainHandItem().is(ModItems.ROADWAY_STAKE.get())) {
            com.github.cerealklla.settlemynts.roadway.RoadwayStakeEntity anchor =
                    com.github.cerealklla.settlemynts.roadway.RoadwayStakeLeash.resolveGhostAnchor(serverLevel, player.getUUID());
            if (anchor != null) {
                anchorPos = net.minecraft.core.BlockPos.containing(anchor.getX(), anchor.getY(), anchor.getZ());
            }
        }
        PacketDistributor.sendToPlayer(player, new com.github.cerealklla.settlemynts.founding.AnchorDistancePayload(anchorPos != null, anchorPos == null ? net.minecraft.core.BlockPos.ZERO : anchorPos));
    }

    /**
     * Rope Fence rework (see decisions.md) -- every tick (unlike the 10-tick-cadence preview above;
     * this needs to feel responsive), physically pulls any player past {@link
     * RopeFenceLeash#MAX_ROPE_LENGTH_BLOCKS} of their current leash anchor back toward it, for both
     * the ghost plot-staking mechanic (holding a Plot Placement Stake) and the real, standalone Rope
     * Fence Post item -- same category of "server nudges velocity" as vanilla's own leashed-mob-
     * pulls-owner-back logic, just applied to the player instead of a mob. A player no longer holding
     * the relevant item has their anchor cleared outright, which is what makes "the rope disappears"
     * on an item swap.
     */
    @SubscribeEvent
    public void onLeashTick(ServerTickEvent.Post event) {
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            if (!(player.level() instanceof ServerLevel serverLevel)) {
                continue;
            }
            if (player.getMainHandItem().is(ModItems.PLOT_PLACEMENT_STAKE.get())) {
                GhostPlotStakeEntity anchor = RopeFenceLeash.resolveGhostAnchor(serverLevel, player.getUUID());
                if (anchor != null) {
                    applyLeashPull(player, new Vec3(anchor.getX(), anchor.getY(), anchor.getZ()), RopeFenceLeash.MAX_ROPE_LENGTH_BLOCKS);
                }
            } else {
                RopeFenceLeash.clearGhostAnchor(player.getUUID());
                // Plot Stakes rework (2026-09-30): switching away from the item resets its
                // CurrentPlotID back to blank, same moment the rope itself breaks above -- see
                // PlotPlacementStakeItem's own doc for why this matters (stops a stale PlotID from
                // silently resuming an old, now-disconnected chain once the player switches back).
                // Idempotent/cheap once already blank, so running it every tick the item isn't held
                // (rather than only on the transition tick) needs no extra "was I holding it last
                // tick" state.
                clearCurrentPlotId(player, ModItems.PLOT_PLACEMENT_STAKE.get());
            }

            if (player.getMainHandItem().is(ModBlocks.ROPE_FENCE_POST_ITEM.get())) {
                BlockPos anchorPos = RopeFenceLeash.resolveRealAnchor(player.getUUID());
                if (anchorPos != null) {
                    applyLeashPull(player, Vec3.atCenterOf(anchorPos), RopeFenceLeash.MAX_ROPE_LENGTH_BLOCKS);
                }
            } else {
                RopeFenceLeash.clearRealAnchor(player.getUUID());
            }

            // Roadways Milestone 1 (added 2026-10-06) -- same leash-pull/anchor-clear shape as Plot
            // Stakes above, just with its own separate tracking map (RoadwayStakeLeash) and its own,
            // much longer max distance (design doc: signposts every ~100 blocks on a finished road --
            // Roadway Stakes need to be able to span that same real distance, unlike Plot/Perimeter
            // Stakes which mark a single settlement's own small boundary).
            if (player.getMainHandItem().is(ModItems.ROADWAY_STAKE.get())) {
                com.github.cerealklla.settlemynts.roadway.RoadwayStakeEntity anchor =
                        com.github.cerealklla.settlemynts.roadway.RoadwayStakeLeash.resolveGhostAnchor(serverLevel, player.getUUID());
                if (anchor != null) {
                    applyLeashPull(player, new Vec3(anchor.getX(), anchor.getY(), anchor.getZ()),
                            com.github.cerealklla.settlemynts.roadway.RoadwayStakeEntity.MAX_CONNECTION_LENGTH_BLOCKS);
                }
            } else {
                com.github.cerealklla.settlemynts.roadway.RoadwayStakeLeash.clearGhostAnchor(player.getUUID());
            }

            // Road Access Flag rework (2026-09-30, mirrors Plot Stakes above): the flag has no rope,
            // so this is just the CurrentPlotID reset -- independent of whether Plot Stakes is also
            // being held, since they're two separate items a planner can carry at once.
            if (!player.getMainHandItem().is(ModItems.ROAD_ACCESS_FLAG.get())) {
                clearCurrentPlotId(player, ModItems.ROAD_ACCESS_FLAG.get());
            }

            // Plot Stakes rework (2026-09-30): "when a player walks out of the settlement that
            // generated the Plot Stakes, remove the item from their inventory" -- checked at a coarser
            // cadence (once a second) than the leash pull above, since a boundary exit doesn't need to
            // feel as responsive as the physical rope. Road Access Flag gets the same rule, same day.
            if (player.tickCount % 20 == 0) {
                removeStalePlotItemsOutsideSettlement(player, serverLevel);
            }
        }
    }

    /**
     * Sweeps every Plot Stakes/Road Access Flag item in {@code player}'s inventory (any slot, not
     * just the held one -- the rule is about carrying it while outside the settlement, not holding
     * it) and removes any whose granting settlement's real founded perimeter no longer contains the
     * player's position. A settlement with no registered core polygon yet (still being founded, not
     * finalized) is skipped rather than treated as "outside" -- both items only ever exist for
     * already-finalized settlements in practice, but failing open here costs nothing and avoids
     * punishing an edge case this rework didn't set out to handle.
     */
    private static void removeStalePlotItemsOutsideSettlement(ServerPlayer player, ServerLevel serverLevel) {
        var inventory = player.getInventory();
        BlockPos pos = player.blockPosition();
        boolean removedAny = false;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.is(ModItems.PLOT_PLACEMENT_STAKE.get()) && !stack.is(ModItems.ROAD_ACCESS_FLAG.get())) {
                continue;
            }
            PlotSessionData session = stack.get(ModItems.PLOT_SESSION_DATA);
            if (session == null || !(serverLevel.getEntity(session.ownerCoreId()) instanceof GhostTownHallCoreEntity core)) {
                continue;
            }
            Long coreEntityId = core.getCartographyrCoreEntityId();
            if (coreEntityId == null) {
                continue;
            }
            Optional<GeographicEntity> registered = Cartography.getEntity(serverLevel, new EntityId(coreEntityId));
            if (registered.isEmpty() || !(registered.get().geometry() instanceof Geometry.Polygon corePolygon)) {
                continue;
            }
            if (!corePolygon.contains(pos.getX(), pos.getZ())) {
                inventory.setItem(slot, ItemStack.EMPTY);
                removedAny = true;
            }
        }
        if (removedAny) {
            RopeFenceLeash.clearGhostAnchor(player.getUUID());
            player.sendSystemMessage(Component.literal("You left the settlement -- your Plot Stakes/Road Access Flag have been removed."));
        }
    }

    private static void clearCurrentPlotId(ServerPlayer player, net.minecraft.world.item.Item item) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.is(item)) {
                continue;
            }
            PlotSessionData session = stack.get(ModItems.PLOT_SESSION_DATA);
            if (session != null && session.plotSessionId() != null) {
                stack.set(ModItems.PLOT_SESSION_DATA, new PlotSessionData(session.ownerCoreId(), null));
            }
        }
    }

    private static void applyLeashPull(ServerPlayer player, Vec3 anchorPos, double maxRopeLengthBlocks) {
        Vec3 playerPos = player.position();
        double distance = anchorPos.distanceTo(playerPos);
        if (distance <= maxRopeLengthBlocks) {
            return;
        }
        double overshoot = distance - maxRopeLengthBlocks;
        Vec3 pull = anchorPos.subtract(playerPos).normalize().scale(Math.min(0.4, overshoot * 0.15));
        player.setDeltaMovement(player.getDeltaMovement().add(pull));
        player.hurtMarked = true; // Forces the velocity change to sync to the client, same as any other server-side push.
    }

    private static PlotSessionData resolveRoadAccessFlagSession(ServerPlayer player) {
        ItemStack main = player.getMainHandItem();
        if (main.is(ModItems.ROAD_ACCESS_FLAG.get())) {
            PlotSessionData session = main.get(ModItems.PLOT_SESSION_DATA);
            if (session != null) {
                return session;
            }
        }
        ItemStack off = player.getOffhandItem();
        if (off.is(ModItems.ROAD_ACCESS_FLAG.get())) {
            return off.get(ModItems.PLOT_SESSION_DATA);
        }
        return null;
    }
}
