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
import com.github.cerealklla.settlemynts.founding.SetTaxRatePayload;
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
        NeoForge.EVENT_BUS.register(new com.github.cerealklla.settlemynts.bills.PlotBillBoxListener());
        if (ModList.get().isLoaded("yconomics")) {
            com.github.cerealklla.settlemynts.zone.RentLedgerListener.register();
        }
        NeoForge.EVENT_BUS.register(new com.github.cerealklla.settlemynts.guardhouse.GuardSpawnTicker());
        NeoForge.EVENT_BUS.register(new com.github.cerealklla.settlemynts.guardhouse.GuardKitStandRemovalListener());
        // PlotShopProximityTicker is no longer a tick-event subscriber as of 2026-10-12 -- it's
        // purely static now, driven by ProximityScanPayload instead of PlayerTickEvent (see its own
        // doc). No registration needed here any more.
        NeoForge.EVENT_BUS.register(new com.github.cerealklla.settlemynts.resident.ResidentSpawnTicker());
        NeoForge.EVENT_BUS.register(new com.github.cerealklla.settlemynts.resident.ResidentConversionGuard());
        NeoForge.EVENT_BUS.register(new com.github.cerealklla.settlemynts.zone.ShopMidnightRestockTicker());
        NeoForge.EVENT_BUS.register(new com.github.cerealklla.settlemynts.zone.ShopCraftingDebounceTicker());
        NeoForge.EVENT_BUS.register(new com.github.cerealklla.settlemynts.lumberyard.LumberjackSpawnTicker());
        NeoForge.EVENT_BUS.register(new com.github.cerealklla.settlemynts.farm.FarmerSpawnTicker());
        NeoForge.EVENT_BUS.register(new com.github.cerealklla.settlemynts.mineshaft.MineshaftSpawnTicker());
        NeoForge.EVENT_BUS.register(new com.github.cerealklla.settlemynts.zone.PlotNpcDeathListener());
        NeoForge.EVENT_BUS.register(new com.github.cerealklla.settlemynts.zone.NpcMaintenanceTicker());
        NeoForge.EVENT_BUS.register(new com.github.cerealklla.settlemynts.founding.TownHallCoreLocatorTicker());
        NeoForge.EVENT_BUS.register(new com.github.cerealklla.settlemynts.founding.LocatorCancelListener());
        NeoForge.EVENT_BUS.register(new com.github.cerealklla.settlemynts.zone.PlotStakeTossGuard());
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
        NeoForge.EVENT_BUS.register(new com.github.cerealklla.settlemynts.diagnostics.LagDiagnostics());
        NeoForge.EVENT_BUS.register(new com.github.cerealklla.settlemynts.watchtower.WatchTowerChunkLoadListener());
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
        modEventBus.addListener(com.github.cerealklla.settlemynts.zone.MidnightChunkWaker::registerController);
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
        event.put(ModEntities.MINER_WORKER.get(), com.github.cerealklla.settlemynts.mineshaft.MinerWorkerEntity.createAttributes().build());
        // Guard Kit Stand overhaul (2026-10-16) -- GuardKitStandEntity extends vanilla's own
        // ArmorStand directly with no custom attribute needs, so it reuses vanilla's own
        // ArmorStand#createAttributes() builder rather than inventing a duplicate. Missing this
        // entirely (a real live bug, caught via a crash report) throws a NullPointerException the
        // instant one is constructed -- EVERY LivingEntity subclass needs a registered
        // AttributeSupplier for its own EntityType, confirmed against the decompiled source; ghost
        // marker entities never needed this because none of them are LivingEntity.
        event.put(ModEntities.GUARD_KIT_STAND.get(), net.minecraft.world.entity.decoration.ArmorStand.createAttributes().build());
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

        registrar.playToServer(SetTaxRatePayload.TYPE, SetTaxRatePayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player
                            && player.level().getEntity(payload.coreEntityId()) instanceof GhostTownHallCoreEntity core
                            && core.isTownPlanner(player.getUUID())) {
                        core.setTaxPerItem(payload.taxPerItem());
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

        registrar.playToClient(com.github.cerealklla.settlemynts.plotsign.ShopGoldUpdatePayload.TYPE,
                com.github.cerealklla.settlemynts.plotsign.ShopGoldUpdatePayload.STREAM_CODEC,
                (payload, context) -> com.github.cerealklla.settlemynts.plotsign.ClientPlotSignRequests.requestGoldUpdate(payload));

        registrar.playToClient(com.github.cerealklla.settlemynts.plotsign.ShopStockUpdatePayload.TYPE,
                com.github.cerealklla.settlemynts.plotsign.ShopStockUpdatePayload.STREAM_CODEC,
                (payload, context) -> com.github.cerealklla.settlemynts.plotsign.ClientPlotSignRequests.requestStockUpdate(payload));

        registrar.playToServer(com.github.cerealklla.settlemynts.plotsign.BuyFromShopPayload.TYPE,
                com.github.cerealklla.settlemynts.plotsign.BuyFromShopPayload.STREAM_CODEC,
                (payload, context) -> buyFromShop(payload, context));

        registrar.playToServer(com.github.cerealklla.settlemynts.plotsign.SellToShopPayload.TYPE,
                com.github.cerealklla.settlemynts.plotsign.SellToShopPayload.STREAM_CODEC,
                (payload, context) -> sellToShop(payload, context));

        registrar.playToServer(com.github.cerealklla.settlemynts.plotsign.SetShopListingsPayload.TYPE,
                com.github.cerealklla.settlemynts.plotsign.SetShopListingsPayload.STREAM_CODEC,
                (payload, context) -> setShopListings(payload, context));

        // Public shop wishlist (2026-10-10) -- see zone.ShopWishlist's own doc.
        registrar.playToServer(com.github.cerealklla.settlemynts.plotsign.RequestShopWishlistPayload.TYPE,
                com.github.cerealklla.settlemynts.plotsign.RequestShopWishlistPayload.STREAM_CODEC,
                (payload, context) -> requestShopWishlist(payload, context));

        registrar.playToClient(com.github.cerealklla.settlemynts.plotsign.OpenShopWishlistPayload.TYPE,
                com.github.cerealklla.settlemynts.plotsign.OpenShopWishlistPayload.STREAM_CODEC,
                (payload, context) -> com.github.cerealklla.settlemynts.plotsign.ClientPlotSignRequests.requestWishlist(payload));

        registrar.playToServer(com.github.cerealklla.settlemynts.plotsign.SellToShopWishlistPayload.TYPE,
                com.github.cerealklla.settlemynts.plotsign.SellToShopWishlistPayload.STREAM_CODEC,
                (payload, context) -> sellToShopWishlist(payload, context));

        // NPC Menu overhaul (2026-10-16, explicit user spec) -- "G"/right-click both land on
        // requestNpcMenu; its three follow-up actions (Change Job's picker is a pure client-side
        // screen, no round trip needed to open it).
        registrar.playToServer(com.github.cerealklla.settlemynts.plotsign.RequestNpcMenuPayload.TYPE,
                com.github.cerealklla.settlemynts.plotsign.RequestNpcMenuPayload.STREAM_CODEC,
                (payload, context) -> requestNpcMenu(payload, context));

        registrar.playToClient(com.github.cerealklla.settlemynts.plotsign.OpenNpcMenuPayload.TYPE,
                com.github.cerealklla.settlemynts.plotsign.OpenNpcMenuPayload.STREAM_CODEC,
                (payload, context) -> com.github.cerealklla.settlemynts.plotsign.ClientPlotSignRequests.requestNpcMenu(payload));

        registrar.playToServer(com.github.cerealklla.settlemynts.plotsign.SetPlotJobPayload.TYPE,
                com.github.cerealklla.settlemynts.plotsign.SetPlotJobPayload.STREAM_CODEC,
                (payload, context) -> setPlotJob(payload, context));

        registrar.playToServer(com.github.cerealklla.settlemynts.plotsign.RequestFireNpcPayload.TYPE,
                com.github.cerealklla.settlemynts.plotsign.RequestFireNpcPayload.STREAM_CODEC,
                (payload, context) -> requestFireNpc(payload, context));

        // Quest menu inside the same "Press H" chat screen (2026-10-10) -- see
        // bridge.MyssionsQuestBridge's own doc.
        registrar.playToServer(com.github.cerealklla.settlemynts.plotsign.AcceptBoardOfferPayload.TYPE,
                com.github.cerealklla.settlemynts.plotsign.AcceptBoardOfferPayload.STREAM_CODEC,
                (payload, context) -> acceptBoardOffer(payload, context));

        registrar.playToServer(com.github.cerealklla.settlemynts.plotsign.CompleteQuestPayload.TYPE,
                com.github.cerealklla.settlemynts.plotsign.CompleteQuestPayload.STREAM_CODEC,
                (payload, context) -> completeQuest(payload, context));

        registrar.playToServer(com.github.cerealklla.settlemynts.plotsign.AcceptRoadQuestOfferPayload.TYPE,
                com.github.cerealklla.settlemynts.plotsign.AcceptRoadQuestOfferPayload.STREAM_CODEC,
                (payload, context) -> acceptRoadQuestOffer(payload, context));

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

        // "Press G to talk" proximity prompt (2026-10-05, reworked 2026-10-16 for the NPC Menu) --
        // see PlotShopProximityTicker's own doc.
        registrar.playToClient(com.github.cerealklla.settlemynts.plotsign.PlotShopPromptPayload.TYPE,
                com.github.cerealklla.settlemynts.plotsign.PlotShopPromptPayload.STREAM_CODEC,
                (payload, context) -> com.github.cerealklla.settlemynts.plotsign.client.ClientShopPromptState.set(payload.present(), payload.anchor()));

        // Personal "!" head marker (2026-10-11) -- see PersonalQuestMarkerPayload's own doc.
        registrar.playToClient(com.github.cerealklla.settlemynts.plotsign.PersonalQuestMarkerPayload.TYPE,
                com.github.cerealklla.settlemynts.plotsign.PersonalQuestMarkerPayload.STREAM_CODEC,
                (payload, context) -> com.github.cerealklla.settlemynts.plotsign.client.ClientPersonalQuestMarkerState.set(payload.entityIds()));

        // Client-triggered proximity scan (2026-10-12) -- see ProximityScanPayload/
        // PlotShopProximityTicker's own docs. Replaces the old server-side polling ticker.
        registrar.playToServer(com.github.cerealklla.settlemynts.plotsign.ProximityScanPayload.TYPE,
                com.github.cerealklla.settlemynts.plotsign.ProximityScanPayload.STREAM_CODEC,
                (payload, context) -> handleProximityScan(payload, context));

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

        registrar.playToServer(com.github.cerealklla.settlemynts.plotsign.RequestHireNpcPayload.TYPE,
                com.github.cerealklla.settlemynts.plotsign.RequestHireNpcPayload.STREAM_CODEC,
                (payload, context) -> requestHireNpc(payload, context));

        registrar.playToServer(com.github.cerealklla.settlemynts.plotsign.RequestEstablishHighwayMapPayload.TYPE,
                com.github.cerealklla.settlemynts.plotsign.RequestEstablishHighwayMapPayload.STREAM_CODEC,
                (payload, context) -> requestEstablishHighwayMap(payload, context));

        registrar.playToClient(com.github.cerealklla.settlemynts.plotsign.EstablishHighwayCandidatesPayload.TYPE,
                com.github.cerealklla.settlemynts.plotsign.EstablishHighwayCandidatesPayload.STREAM_CODEC,
                (payload, context) -> com.github.cerealklla.settlemynts.plotsign.ClientPlotSignRequests.requestEstablishHighwayMap(payload));

        registrar.playToServer(com.github.cerealklla.settlemynts.plotsign.RequestCreateHighwayQuestPayload.TYPE,
                com.github.cerealklla.settlemynts.plotsign.RequestCreateHighwayQuestPayload.STREAM_CODEC,
                (payload, context) -> requestCreateHighwayQuest(payload, context));

        registrar.playToClient(com.github.cerealklla.settlemynts.plotsign.OpenPlotManagementPayload.TYPE,
                com.github.cerealklla.settlemynts.plotsign.OpenPlotManagementPayload.STREAM_CODEC,
                (payload, context) -> com.github.cerealklla.settlemynts.plotsign.ClientPlotSignRequests.requestPlotManagement(payload));

        // "Defense Spending" (explicit user spec, 2026-10-16) -- the Ghost Town Hall Core's own
        // direct right-click menu (FoundingScreen), NOT the Plot Config Sign -- a real correction,
        // see decisions.md.
        registrar.playToServer(com.github.cerealklla.settlemynts.founding.RequestDefenseSpendingPayload.TYPE,
                com.github.cerealklla.settlemynts.founding.RequestDefenseSpendingPayload.STREAM_CODEC,
                (payload, context) -> requestDefenseSpending(payload, context));

        registrar.playToClient(com.github.cerealklla.settlemynts.founding.OpenDefenseSpendingMenuPayload.TYPE,
                com.github.cerealklla.settlemynts.founding.OpenDefenseSpendingMenuPayload.STREAM_CODEC,
                (payload, context) -> com.github.cerealklla.settlemynts.founding.ClientFoundingRequests.requestDefenseSpendingScreen(payload));

        registrar.playToServer(com.github.cerealklla.settlemynts.founding.SetDefenseSpendingPayload.TYPE,
                com.github.cerealklla.settlemynts.founding.SetDefenseSpendingPayload.STREAM_CODEC,
                (payload, context) -> setDefenseSpending(payload, context));

        // Guard Kit Stand overhaul (design doc Section 14a, reworked 2026-10-16) -- "Get Guard Kit
        // Stands" on the Plot Config Sign.
        registrar.playToServer(com.github.cerealklla.settlemynts.guardhouse.RequestGuardKitStandsPayload.TYPE,
                com.github.cerealklla.settlemynts.guardhouse.RequestGuardKitStandsPayload.STREAM_CODEC,
                (payload, context) -> requestGuardKitStands(payload, context));

        // The Kit Menu itself -- opened directly from GuardKitStandEntity#interact (a right-click is
        // already a server-side event, no request payload needed for the initial open).
        registrar.playToClient(com.github.cerealklla.settlemynts.guardhouse.OpenGuardKitStandMenuPayload.TYPE,
                com.github.cerealklla.settlemynts.guardhouse.OpenGuardKitStandMenuPayload.STREAM_CODEC,
                (payload, context) -> com.github.cerealklla.settlemynts.guardhouse.ClientGuardhouseRequests.requestMenu(payload));

        registrar.playToServer(com.github.cerealklla.settlemynts.guardhouse.PickUpGuardKitStandPayload.TYPE,
                com.github.cerealklla.settlemynts.guardhouse.PickUpGuardKitStandPayload.STREAM_CODEC,
                (payload, context) -> pickUpGuardKitStand(payload, context));

        registrar.playToServer(com.github.cerealklla.settlemynts.guardhouse.RequestCreateKytForStandPayload.TYPE,
                com.github.cerealklla.settlemynts.guardhouse.RequestCreateKytForStandPayload.STREAM_CODEC,
                (payload, context) -> requestCreateKytForStand(payload, context));

        registrar.playToServer(com.github.cerealklla.settlemynts.guardhouse.RequestEditKytForStandPayload.TYPE,
                com.github.cerealklla.settlemynts.guardhouse.RequestEditKytForStandPayload.STREAM_CODEC,
                (payload, context) -> requestEditKytForStand(payload, context));

        // "Select Kyt" (optional Kyt soft dependency -- see bridge.KytGuardhouseBridge).
        registrar.playToServer(com.github.cerealklla.settlemynts.guardhouse.RequestGuardKitStandKytNamesPayload.TYPE,
                com.github.cerealklla.settlemynts.guardhouse.RequestGuardKitStandKytNamesPayload.STREAM_CODEC,
                (payload, context) -> requestGuardKitStandKytNames(payload, context));

        registrar.playToClient(com.github.cerealklla.settlemynts.guardhouse.OpenGuardKitStandKytPickerPayload.TYPE,
                com.github.cerealklla.settlemynts.guardhouse.OpenGuardKitStandKytPickerPayload.STREAM_CODEC,
                (payload, context) -> com.github.cerealklla.settlemynts.guardhouse.ClientGuardhouseRequests.requestKytPicker(payload));

        registrar.playToServer(com.github.cerealklla.settlemynts.guardhouse.SetStandKytPayload.TYPE,
                com.github.cerealklla.settlemynts.guardhouse.SetStandKytPayload.STREAM_CODEC,
                (payload, context) -> setStandKyt(payload, context));

        registrar.playToServer(com.github.cerealklla.settlemynts.guardhouse.SetStandSparesOnHandPayload.TYPE,
                com.github.cerealklla.settlemynts.guardhouse.SetStandSparesOnHandPayload.STREAM_CODEC,
                (payload, context) -> setStandSparesOnHand(payload, context));

        // "Preferred Food" on the Guardhouse's own Food Stores screen.
        registrar.playToServer(com.github.cerealklla.settlemynts.guardhouse.SetPreferredFoodPayload.TYPE,
                com.github.cerealklla.settlemynts.guardhouse.SetPreferredFoodPayload.STREAM_CODEC,
                (payload, context) -> setPreferredFood(payload, context));

        // "Auto-Drop" keybind (2026-10-17) -- see zone.AutoDropper's own doc.
        registrar.playToServer(com.github.cerealklla.settlemynts.zone.RequestAutoDropPayload.TYPE,
                com.github.cerealklla.settlemynts.zone.RequestAutoDropPayload.STREAM_CODEC,
                (payload, context) -> requestAutoDrop(payload, context));

        // "Enable/Disable" toggle on the Watch Tower Beacon's own fuel screen (2026-10-22).
        registrar.playToServer(com.github.cerealklla.settlemynts.watchtower.SetWatchTowerEnabledPayload.TYPE,
                com.github.cerealklla.settlemynts.watchtower.SetWatchTowerEnabledPayload.STREAM_CODEC,
                (payload, context) -> setWatchTowerEnabled(payload, context));
    }

    private static void requestAutoDrop(com.github.cerealklla.settlemynts.zone.RequestAutoDropPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        com.github.cerealklla.settlemynts.zone.AutoDropper.autoDrop(player, serverLevel);
    }

    /**
     * "Get Guard Kit Stands" button -- re-checks {@code zone.PlotPermissions#canManage} and re-reads
     * the plot's own live {@code zone.PlotRecord#guardKitStandsIssued} count (never trusts the
     * button's earlier label) before granting every currently-available stand item in one go (real
     * live report, 2026-10-16: granting just one per click read as broken/confusing against a button
     * labeled with the full available count) and incrementing that count by the same amount, all
     * synchronously within this one handler call -- see {@link
     * com.github.cerealklla.settlemynts.plotsign.OpenPlotConfigSignMenuPayload}'s own doc for why
     * that's sufficient to prevent two concurrent clicks from over-granting past the Tier cap.
     */
    private static void requestGuardKitStands(com.github.cerealklla.settlemynts.guardhouse.RequestGuardKitStandsPayload payload, IPayloadContext context) {
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
        // Resolved live via constructionBoxId, not the stale plot.boxPos() snapshot (2026-10-16 fix).
        int boxTier = ModList.get().isLoaded("blueprynts")
                ? com.github.cerealklla.settlemynts.bridge.BlueprintsConstructionBridge.resolveTier(serverLevel, plot)
                : 0;
        int available = com.github.cerealklla.settlemynts.guardhouse.GuardhouseConstants.capacityForTier(boxTier) - plot.guardKitStandsIssued();
        if (available <= 0) {
            player.sendSystemMessage(Component.literal("No more Guard Kit Stands are available for this plot's current Tier."));
            return;
        }
        core.updatePlot(plot.withGuardKitStandsIssued(plot.guardKitStandsIssued() + available));
        for (int i = 0; i < available; i++) {
            net.minecraft.world.item.ItemStack item = new net.minecraft.world.item.ItemStack(
                    com.github.cerealklla.settlemynts.registration.ModItems.GUARD_KIT_STAND.get());
            item.set(com.github.cerealklla.settlemynts.registration.ModItems.GUARD_KIT_STAND_DATA.get(),
                    new com.github.cerealklla.settlemynts.guardhouse.GuardKitStandData(
                            UUID.randomUUID(), sign.settlementCoreId(), plot.plotId(), Optional.empty(), Optional.empty(), 0L, 0));
            if (!player.getInventory().add(item)) {
                player.drop(item, false);
            }
        }
    }

    /** "Pick Up" on {@code client.GuardKitStandScreen} -- re-checks permission directly against the live entity, never trusts the client. */
    private static void pickUpGuardKitStand(com.github.cerealklla.settlemynts.guardhouse.PickUpGuardKitStandPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        resolveManageableStand(serverLevel, player, payload.standId()).ifPresent(stand ->
                com.github.cerealklla.settlemynts.guardhouse.GuardKitStandInteraction.pickUp(player, stand));
    }

    /** "Create Kyt" on {@code client.GuardKitStandScreen} -- binds {@code name} to the stand immediately, then opens Kyt's real Create screen for it. */
    private static void requestCreateKytForStand(com.github.cerealklla.settlemynts.guardhouse.RequestCreateKytForStandPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!com.github.cerealklla.settlemynts.bridge.KytGuardhouseBridge.isLoaded()) {
            return;
        }
        resolveManageableStand(serverLevel, player, payload.standId()).ifPresent(stand -> {
            stand.setBoundKytName(payload.name());
            com.github.cerealklla.settlemynts.guardhouse.GuardKitStandPreferredInventory.recalculate(
                    serverLevel, stand.settlementCoreId(), stand.plotId());
            com.github.cerealklla.settlemynts.bridge.KytGuardhouseBridge.openCreateScreen(player, payload.name());
        });
    }

    /** "Edit Kyt" on {@code client.GuardKitStandScreen} -- opens Kyt's real Edit screen for the stand's already-bound name. */
    private static void requestEditKytForStand(com.github.cerealklla.settlemynts.guardhouse.RequestEditKytForStandPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!com.github.cerealklla.settlemynts.bridge.KytGuardhouseBridge.isLoaded()) {
            return;
        }
        resolveManageableStand(serverLevel, player, payload.standId()).ifPresent(stand ->
                stand.boundKytName().ifPresent(name -> com.github.cerealklla.settlemynts.bridge.KytGuardhouseBridge.openEditScreen(player, name)));
    }

    /** "Select Kyt" button on {@code client.GuardKitStandScreen} -- replies with the server's real Kyt name list (see {@code bridge.KytGuardhouseBridge}). A Kyt-less server never reaches this handler at all in practice (the screen's own "Select Kyt" row is hidden client-side via {@code OpenGuardKitStandMenuPayload#kytAvailable}), but this still guards {@link com.github.cerealklla.settlemynts.bridge.KytGuardhouseBridge#isLoaded} itself before touching the bridge, same isolation convention as every other optional dependency here. */
    private static void requestGuardKitStandKytNames(com.github.cerealklla.settlemynts.guardhouse.RequestGuardKitStandKytNamesPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (resolveManageableStand(serverLevel, player, payload.standId()).isEmpty()) {
            return;
        }
        if (!com.github.cerealklla.settlemynts.bridge.KytGuardhouseBridge.isLoaded()) {
            return;
        }
        PacketDistributor.sendToPlayer(player, new com.github.cerealklla.settlemynts.guardhouse.OpenGuardKitStandKytPickerPayload(
                payload.standId(), com.github.cerealklla.settlemynts.bridge.KytGuardhouseBridge.listKytNames()));
    }

    /** "Select"/"Clear" on {@code client.SelectKytScreen} -- re-checks permission the same way, never trusts the client's own name. */
    private static void setStandKyt(com.github.cerealklla.settlemynts.guardhouse.SetStandKytPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        resolveManageableStand(serverLevel, player, payload.standId()).ifPresent(stand -> {
            stand.setBoundKytName(payload.kytName());
            com.github.cerealklla.settlemynts.guardhouse.GuardKitStandPreferredInventory.recalculate(
                    serverLevel, stand.settlementCoreId(), stand.plotId());
        });
    }

    /** "Keep Spares On Hand" + Save on {@code client.GuardKitStandScreen} -- re-checks permission the same way, never trusts the client's own value. */
    private static void setStandSparesOnHand(com.github.cerealklla.settlemynts.guardhouse.SetStandSparesOnHandPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        resolveManageableStand(serverLevel, player, payload.standId()).ifPresent(stand -> {
            stand.setSparesOnHand(payload.sparesOnHand());
            com.github.cerealklla.settlemynts.guardhouse.GuardKitStandPreferredInventory.recalculate(
                    serverLevel, stand.settlementCoreId(), stand.plotId());
        });
    }

    /** "Preferred Food" + Save on {@code client.GuardhouseFoodScreen} -- re-checks {@code guardhouse.GuardhouseBlockEntity#canManage} directly, never trusts the client's earlier flag. */
    private static void setPreferredFood(com.github.cerealklla.settlemynts.guardhouse.SetPreferredFoodPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!(serverLevel.getBlockEntity(payload.guardhousePos()) instanceof com.github.cerealklla.settlemynts.guardhouse.GuardhouseBlockEntity guardhouse)
                || !guardhouse.canManage(player)) {
            return;
        }
        guardhouse.setPreferredFoodValue(payload.preferredFoodValue());
    }

    /** "Enable/Disable" on {@code watchtower.client.WatchTowerBeaconScreen} -- re-checks {@code watchtower.WatchTowerBeaconBlockEntity#canManage} directly, never trusts the client's earlier flag. */
    private static void setWatchTowerEnabled(com.github.cerealklla.settlemynts.watchtower.SetWatchTowerEnabledPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!(serverLevel.getBlockEntity(payload.beaconPos()) instanceof com.github.cerealklla.settlemynts.watchtower.WatchTowerBeaconBlockEntity beacon)
                || !beacon.canManage(player)) {
            return;
        }
        beacon.setManuallyEnabled(payload.enabled());
    }

    /** Shared permission + live-entity re-check for every Kit Menu action -- resolves the stand by UUID (via {@link com.github.cerealklla.settlemynts.guardhouse.GuardKitStandIndex}) and re-checks {@code zone.PlotPermissions#canManage} against its own plot, never trusting the client's earlier flag. */
    private static Optional<com.github.cerealklla.settlemynts.guardhouse.GuardKitStandEntity> resolveManageableStand(
            ServerLevel serverLevel, ServerPlayer player, UUID standId) {
        if (!(serverLevel.getEntity(standId) instanceof com.github.cerealklla.settlemynts.guardhouse.GuardKitStandEntity stand)) {
            return Optional.empty();
        }
        if (stand.settlementCoreId() == null || !(serverLevel.getEntity(stand.settlementCoreId()) instanceof GhostTownHallCoreEntity core)) {
            return Optional.empty();
        }
        PlotRecord plot = core.getPlots().stream().filter(p -> p.plotId().equals(stand.plotId())).findFirst().orElse(null);
        if (plot == null || !PlotPermissions.canManage(plot, core, player.getUUID())) {
            return Optional.empty();
        }
        return Optional.of(stand);
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
     * Server-side half of the 2026-10-12 proximity rework -- see {@code
     * plotsign.PlotShopProximityTicker}/{@code plotsign.ProximityScanPayload}'s own docs. Just
     * resolves the real {@link ServerLevel} and hands off; all the actual re-validation/eligibility
     * logic lives in the ticker class, same as every other anchor-resolving handler in this file.
     */
    private static void handleProximityScan(com.github.cerealklla.settlemynts.plotsign.ProximityScanPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        com.github.cerealklla.settlemynts.plotsign.PlotShopProximityTicker.handleProximityScan(
                player, serverLevel, payload.nearCandidates(), payload.farVillagerEntityIds());
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
            //
            // Per-quality variants (2026-10-10, e.g. Lyfe's crafted food baked at different Cook/
            // structure quality) -- grouped by exact {@code zone.ShopResource} (item id + the stack's
            // own custom name when present, see ShopResource#ofExactItem) instead of plain item id, so
            // two different qualities of the same base item get their own independent row/price. Only
            // concrete (non-tag) resources ever carry a variant -- a tag listing stays generic, same
            // as a Planned Inventory target.
            java.util.List<net.minecraft.world.Container> manageBoxes =
                    com.github.cerealklla.settlemynts.api.Settlemynts.resolvePlotBoxes(serverLevel, plot.plotId());
            java.util.Map<com.github.cerealklla.settlemynts.zone.ShopResource, com.github.cerealklla.settlemynts.api.Settlemynts.VariantStock> stockByVariant =
                    com.github.cerealklla.settlemynts.api.Settlemynts.scanItemStockByVariant(manageBoxes);

            java.util.Map<com.github.cerealklla.settlemynts.zone.ShopResource, Integer> listedPriceByResource = new java.util.LinkedHashMap<>();
            java.util.List<com.github.cerealklla.settlemynts.plotsign.ShopInventoryEntry> tagRows = new java.util.ArrayList<>();
            for (com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge.ShopListingView view : views) {
                if (view.resource().tag().isPresent()) {
                    net.minecraft.tags.TagKey<net.minecraft.world.item.Item> tag = view.resource().tag().get();
                    int tagStock = stock.entrySet().stream()
                            .filter(e -> view.resource().matches(new net.minecraft.world.item.ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(e.getKey()))))
                            .mapToInt(java.util.Map.Entry::getValue).sum();
                    tagRows.add(new com.github.cerealklla.settlemynts.plotsign.ShopInventoryEntry(tag.location(), true, java.util.Optional.empty(), tagStock, view.pricePerUnit(), 0));
                } else {
                    listedPriceByResource.put(view.resource(), view.pricePerUnit());
                }
            }

            java.util.Set<com.github.cerealklla.settlemynts.zone.ShopResource> suppressedSet = plot.suppressedShopResources().stream()
                    .filter(s -> !s.isTag())
                    .map(s -> s.customName().isPresent()
                            ? com.github.cerealklla.settlemynts.zone.ShopResource.ofExactItem(s.resourceKey(), s.customName().get())
                            : com.github.cerealklla.settlemynts.zone.ShopResource.ofItem(s.resourceKey()))
                    .collect(java.util.stream.Collectors.toSet());

            java.util.Map<net.minecraft.resources.Identifier, Integer> catalogDefaultByItem = new java.util.LinkedHashMap<>();
            for (com.github.cerealklla.settlemynts.zone.SeedListing seed : com.github.cerealklla.settlemynts.zone.ShopSeeding.catalogSeedListings(serverLevel, plot, resolved.get().anchorPos())) {
                seed.listingResource().itemId().ifPresent(itemId -> catalogDefaultByItem.put(itemId, seed.pricePerUnit()));
            }

            net.minecraft.resources.Identifier goldNuggetId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(net.minecraft.world.item.Items.GOLD_NUGGET);
            java.util.Set<com.github.cerealklla.settlemynts.zone.ShopResource> allKeys = new java.util.LinkedHashSet<>();
            allKeys.addAll(stockByVariant.keySet());
            allKeys.addAll(listedPriceByResource.keySet());
            allKeys.addAll(suppressedSet);
            for (net.minecraft.resources.Identifier catalogItem : catalogDefaultByItem.keySet()) {
                allKeys.add(com.github.cerealklla.settlemynts.zone.ShopResource.ofItem(catalogItem));
            }
            // Gold Nuggets are the shop's own currency, not merchandise -- never a row here, same as
            // the sell-mode listing table already (correctly) excludes it.
            allKeys.remove(com.github.cerealklla.settlemynts.zone.ShopResource.ofItem(goldNuggetId));

            java.util.List<com.github.cerealklla.settlemynts.plotsign.ShopInventoryEntry> inventory = new java.util.ArrayList<>(tagRows);
            for (com.github.cerealklla.settlemynts.zone.ShopResource key : allKeys) {
                com.github.cerealklla.settlemynts.api.Settlemynts.VariantStock variantStock = stockByVariant.get(key);
                int shopStock = variantStock != null ? variantStock.count() : 0;
                int listedPrice = listedPriceByResource.getOrDefault(key, 0);
                boolean alreadyConfigured = listedPrice > 0 || suppressedSet.contains(key);
                int suggestedPrice = 0;
                if (!alreadyConfigured) {
                    int catalogBase = catalogDefaultByItem.getOrDefault(key.itemId().get(), 0);
                    if (key.customName().isPresent() && variantStock != null && com.github.cerealklla.settlemynts.bridge.LyfeCookingBridge.isLoaded()) {
                        suggestedPrice = catalogBase + com.github.cerealklla.settlemynts.bridge.LyfeCookingBridge.getIcons(variantStock.sample())
                                .map(icons -> (int) Math.floor(icons)).orElse(0);
                    } else {
                        suggestedPrice = catalogBase;
                    }
                }
                inventory.add(new com.github.cerealklla.settlemynts.plotsign.ShopInventoryEntry(
                        key.itemId().get(), false, key.customName(), shopStock, listedPrice, suggestedPrice));
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

        // Real redesign, 2026-10-20 ("I'd like it to look something like this" -- a real four-
        // container cart mockup, replacing the OpenShopPayload-driven button-list screen shipped
        // hours earlier the same day): Buy mode now opens a genuine AbstractContainerMenu
        // (ShopCartMenu) via the normal player.openMenu path instead of a plain payload, so vanilla's
        // own slot click/drag/shift-click machinery does the heavy lifting -- see that class's own
        // doc for the four-lane Shop/Buy/Sell/Player design.
        java.util.List<com.github.cerealklla.settlemynts.plotsign.ShopListingEntry> listings = buildBuyListings(player, views, manageBoxesFor(serverLevel, plot), plot);
        player.openMenu(new com.github.cerealklla.settlemynts.plotsign.ShopCartMenuProvider(
                anchor, plot.plotId(), listings, shopGoldNuggets, playerGoldNuggets));
    }

    private static java.util.List<net.minecraft.world.Container> manageBoxesFor(ServerLevel level, PlotRecord plot) {
        return com.github.cerealklla.settlemynts.api.Settlemynts.resolvePlotBoxes(level, plot.plotId());
    }

    /**
     * Builds the buy-mode listing table rows (price + live stock, per-player Merchant-adjusted
     * prices) -- factored out of {@link #handleRequestShop} (2026-10-10) so {@link
     * #sendShopStockUpdate} can rebuild the same rows with fresh stock after a Buy/Sell without
     * duplicating this logic. {@code boxes} (changed 2026-10-10 from a flat item-id stock map, for
     * per-quality listings) is scanned via {@code api.Settlemynts#scanItemStockByVariant} so a
     * listing's "Shop Stock" column reflects only its own exact quality variant's stock, not every
     * quality of the item combined -- a *plain* (non-variant) listing still naturally sums across
     * every quality, since {@code ShopResource#matches} only checks the custom name when the
     * listing's own resource carries one.
     *
     * <p>{@code plot} (added 2026-10-20) supplies {@link com.github.cerealklla.settlemynts.zone.PlannedInventoryTarget}
     * lookups for each row's {@code plannedInventoryRemaining} -- see {@code ShopListingEntry}'s own
     * doc for why this is the gate on what a player may sell back through {@code plotsign.ShopCartMenu}.
     */
    private static java.util.List<com.github.cerealklla.settlemynts.plotsign.ShopListingEntry> buildBuyListings(
            ServerPlayer player,
            java.util.List<com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge.ShopListingView> views,
            java.util.List<net.minecraft.world.Container> boxes,
            PlotRecord plot) {
        java.util.Map<com.github.cerealklla.settlemynts.zone.ShopResource, com.github.cerealklla.settlemynts.api.Settlemynts.VariantStock> stockByVariant =
                com.github.cerealklla.settlemynts.api.Settlemynts.scanItemStockByVariant(boxes);
        double merchantBonusFraction = com.github.cerealklla.settlemynts.bridge.LyfeMerchantBridge.isLoaded()
                ? com.github.cerealklla.settlemynts.bridge.LyfeMerchantBridge.getPriceBonusFraction(player)
                : 0.0;
        return views.stream()
                .map(l -> {
                    net.minecraft.resources.Identifier key = l.resource().tag().map(t -> t.location()).orElseGet(() -> l.resource().itemId().get());
                    boolean isTag = l.resource().tag().isPresent();
                    int shopStock = stockByVariant.entrySet().stream()
                            .filter(e -> l.resource().matches(e.getValue().sample()))
                            .mapToInt(e -> e.getValue().count())
                            .sum();
                    // Real report, 2026-10-20: the Shop screen showed the generic blank-paper icon for
                    // a Research Note listing instead of its own per-target icon (e.g. a Stone Axe
                    // overlay), even though the tooltip correctly showed "Research Notes - Stone Axe
                    // (Tier 1)" -- that name comes from the listing's own stored customName, but the
                    // icon is driven by a CustomModelData component (see lyfe.research.ResearchNoteItem
                    // #createFor's own doc) that a listing has no field for at all, so the client's
                    // conjured representative stack (ShopCartMenu#representativeStack) was always a
                    // bare, component-less ItemStack -- correct name, generic fallback icon. Pulls the
                    // real CustomModelData strings off a live matching box sample when one exists (the
                    // common case -- a zero-stock listing simply keeps the generic icon, a flagged,
                    // acceptable gap rather than a structural change to Yconomics' own ShopResource).
                    java.util.List<String> customModelDataStrings = stockByVariant.values().stream()
                            .filter(v -> l.resource().matches(v.sample()))
                            .findFirst()
                            .map(v -> v.sample().get(net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA))
                            .map(net.minecraft.world.item.component.CustomModelData::strings)
                            .orElse(java.util.List.of());
                    int effectiveBuyPrice = com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge.effectiveBuyCost(l.pricePerUnit(), merchantBonusFraction);
                    int effectiveSellPrice = com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge.effectiveSellPayout(l.pricePerUnit(), merchantBonusFraction);
                    // Planned Inventory gate (2026-10-20) -- matched by base resource identity only
                    // (ignoring quality/customName, same coarser identity PlannedInventoryTarget itself
                    // uses), and against the item's TOTAL stock across every quality, not just this
                    // listing's own variant-scoped shopStock above.
                    int plannedInventoryRemaining = findPlannedInventoryTarget(plot, key, isTag)
                            .map(target -> {
                                com.github.cerealklla.settlemynts.zone.ShopResource baseResource = isTag
                                        ? com.github.cerealklla.settlemynts.zone.ShopResource.ofTag(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, key))
                                        : com.github.cerealklla.settlemynts.zone.ShopResource.ofItem(key);
                                int baseStock = com.github.cerealklla.settlemynts.zone.ContainerWithdraw.countAvailable(boxes, baseResource);
                                return Math.max(0, target.targetCount() - baseStock);
                            })
                            .orElse(0);
                    return new com.github.cerealklla.settlemynts.plotsign.ShopListingEntry(
                            key, isTag, l.resource().customName(), l.pricePerUnit(), l.buyPricePerUnit(), shopStock, effectiveBuyPrice, effectiveSellPrice,
                            plannedInventoryRemaining, customModelDataStrings);
                })
                .sorted(java.util.Comparator
                        .comparing((com.github.cerealklla.settlemynts.plotsign.ShopListingEntry e) -> e.shopStock() <= 0)
                        .thenComparing(com.github.cerealklla.settlemynts.plotsign.ShopListingEntry::label))
                .toList();
    }

    /** The plot's own {@link com.github.cerealklla.settlemynts.zone.PlannedInventoryTarget} for {@code resourceKey}/{@code isTag}, matched by base resource identity only (no quality/customName concept exists on a target). */
    private static java.util.Optional<com.github.cerealklla.settlemynts.zone.PlannedInventoryTarget> findPlannedInventoryTarget(
            PlotRecord plot, net.minecraft.resources.Identifier resourceKey, boolean isTag) {
        return plot.plannedInventory().stream()
                .filter(t -> t.isTag() == isTag && t.resourceKey().equals(resourceKey))
                .findFirst();
    }

    /** The {@code zone.ShopResource} a {@code ShopListingEntry} represents -- factored out 2026-10-20 since {@link #confirmShopCart}/{@code #autoSellToShopCart} both need it. */
    private static com.github.cerealklla.settlemynts.zone.ShopResource resourceFor(com.github.cerealklla.settlemynts.plotsign.ShopListingEntry listing) {
        return listing.isTag()
                ? com.github.cerealklla.settlemynts.zone.ShopResource.ofTag(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, listing.resourceKey()))
                : listing.customName().isPresent()
                    ? com.github.cerealklla.settlemynts.zone.ShopResource.ofExactItem(listing.resourceKey(), listing.customName().get())
                    : com.github.cerealklla.settlemynts.zone.ShopResource.ofItem(listing.resourceKey());
    }

    /**
     * Pushes a fresh {@code ShopStockUpdatePayload} so an already-open buy-mode Shop screen's "Shop
     * Stock" column actually reflects a just-completed Buy/Sell (real report, 2026-10-10: "When
     * buying/selling from a store the shop stock number is not changing") -- same root cause and
     * same fix shape as {@link #sendShopGoldUpdate}.
     */
    private static void sendShopStockUpdate(ServerPlayer player, ServerLevel level, UUID plotId, UUID shopId) {
        java.util.Optional<PlotRecord> plot = com.github.cerealklla.settlemynts.api.Settlemynts.findPlotRecord(level, plotId);
        if (plot.isEmpty()) {
            return;
        }
        java.util.List<com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge.ShopListingView> views =
                com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge.getListings(level, shopId);
        java.util.List<net.minecraft.world.Container> boxes =
                com.github.cerealklla.settlemynts.api.Settlemynts.resolvePlotBoxes(level, plotId);
        java.util.List<com.github.cerealklla.settlemynts.plotsign.ShopListingEntry> listings = buildBuyListings(player, views, boxes, plot.get());
        PacketDistributor.sendToPlayer(player, new com.github.cerealklla.settlemynts.plotsign.ShopStockUpdatePayload(listings));
    }

    /** "Buy N" click on the real Shop screen -- charges the buyer only for whatever was actually filled. */
    /**
     * The settlement sales tax to apply to a sale of {@code quantity} items worth {@code
     * transactionValue} total (explicit user spec, 2026-10-17: "Taxes are applied any time an item
     * is sold within the settlement... a flat amount, defaulted to 1 nugget per transaction" --
     * corrected 2026-10-21: "it shouldn't be 1 per transaction, it should be 1 per sold item," so
     * this is now the per-unit rate times how many units actually changed hands, not a flat per-click
     * fee), clamped to never exceed the transaction's own value (can't tax more than was actually
     * paid). 0 if {@code owner} isn't a real player-founded settlement (a natural village has no Town
     * Hall to pay) or that settlement has no Town Hall plot yet.
     *
     * <p><b>"Guardhouses do not pay taxes when buying or selling" holds with no special-case code
     * anywhere</b> -- a Guardhouse plot can never have a Shop at all ({@link #buyFromShop}/{@link
     * #sellToShop} both already refuse one with "This plot doesn't have a Shop."), so a Guardhouse
     * is never the taxed seller side of either handler; and a Guardhouse's own NPC-driven food
     * purchases ({@code guardhouse.GuardhouseFoodAutoBuy}) go through a completely separate {@code
     * BillTransferEngine}-based path that never calls this method or {@link #depositSalesTax} at
     * all. Don't "fix" either of those paths to also collect tax later -- the exemption is
     * intentional, not a gap.
     */
    private static int resolveSalesTax(com.github.cerealklla.settlemynts.zone.PlotOwner owner, int quantity, int transactionValue) {
        if (!(owner instanceof GhostTownHallCoreEntity core) || quantity <= 0 || transactionValue <= 0 || core.townHallPlot().isEmpty()) {
            return 0;
        }
        return Math.min(Math.max(0, core.taxPerItem()) * quantity, transactionValue);
    }

    /**
     * Deposits an already-resolved (see {@link #resolveSalesTax}) tax {@code amount} into {@code
     * core}'s Town Hall plot boxes, with a ledger entry on the Town Hall's own side and (when {@code
     * sellerPlot} is non-null, i.e. the seller was a Shop plot rather than a player) a matching
     * paid-out entry on the seller's side too -- same two-sided ledger shape {@code
     * guardhouse.DefenseSpendingTransfer} already uses for its own Town-Hall-to-plot gold transfer.
     * No-op if {@code amount <= 0}.
     */
    private static void depositSalesTax(ServerLevel level, GhostTownHallCoreEntity core, PlotRecord sellerPlot, int amount, String sellerLabel) {
        if (amount <= 0) {
            return;
        }
        PlotRecord townHall = core.townHallPlot().orElse(null);
        if (townHall == null) {
            return;
        }
        java.util.List<net.minecraft.world.Container> townHallBoxes =
                com.github.cerealklla.settlemynts.api.Settlemynts.resolvePlotBoxes(level, townHall.plotId());
        com.github.cerealklla.settlemynts.zone.ContainerDeposit.depositIntoAny(townHallBoxes,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GOLD_NUGGET, amount));
        if (ModList.get().isLoaded("blueprynts")) {
            com.github.cerealklla.settlemynts.bridge.BlueprintsConstructionBridge.appendLedgerEntry(level, townHall,
                    "Sales Tax +" + amount + " from " + sellerLabel);
            if (sellerPlot != null) {
                com.github.cerealklla.settlemynts.bridge.BlueprintsConstructionBridge.appendLedgerEntry(level, sellerPlot,
                        "Sales Tax -" + amount + " to Town Hall");
            }
        }
    }

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
                : payload.customName().isPresent()
                    ? com.github.cerealklla.settlemynts.zone.ShopResource.ofExactItem(payload.resourceKey(), payload.customName().get())
                    : com.github.cerealklla.settlemynts.zone.ShopResource.ofItem(payload.resourceKey());
        java.util.List<net.minecraft.world.Container> boxes = com.github.cerealklla.settlemynts.api.Settlemynts.resolvePlotBoxes(serverLevel, plotId);
        boolean bought = processBuyLine(player, serverLevel, resolvedBuy.get(), boxes, shopId.get(), resource, payload.quantity());
        if (!bought) {
            return;
        }
        finishBuyBatch(player, serverLevel, plotId, shopId.get());
    }

    /**
     * Refactored out of the old single-item {@link #buyFromShop} 2026-10-20, so {@link
     * #confirmShopCart} (the new cart-screen batch path) can process several distinct listings
     * against the SAME already-resolved {@code shopContext}/{@code boxes} instead of re-resolving
     * the full shop-lookup path (a full-world {@code GhostTownHallCoreEntity} scan) once per listing
     * -- the exact same "resolve once, loop many" optimization the shop screen's own earlier cart
     * fix already applied client-side for repeated clicks on one listing, now applied server-side
     * for several different listings in one Confirm. Everything through the sales-tax withholding
     * and ledger entry is unchanged from the original method; the gold/stock-refresh/auto-crafting-
     * check steps are split out into {@link #finishBuyBatch} so they only run once per *batch* (one
     * real purchase click, or one whole cart confirm), not once per listing within a confirm.
     * Returns {@code false} (and sends the player a message) if nothing was actually bought.
     */
    private static boolean processBuyLine(ServerPlayer player, ServerLevel serverLevel, ShopContext shopContext,
                                           java.util.List<net.minecraft.world.Container> boxes, UUID shopId,
                                           com.github.cerealklla.settlemynts.zone.ShopResource resource, int quantity) {
        com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge.PurchaseResult result =
                com.github.cerealklla.settlemynts.api.Settlemynts.purchaseFromSettlementShop(serverLevel, shopContext.plot().plotId(), shopId, resource, quantity, boxes);
        // Captured once, up front -- real report, 2026-10-09: "Buying 0 for # gold." PurchaseResult#
        // filled() isn't a stored count, it re-sums itemsReceived()'s own ItemStacks every call, and
        // player.getInventory().add(stack) below mutates those same stack instances in place (vanilla
        // shrinks a stack to empty as it's deposited) -- so every filled() call AFTER that loop saw 0,
        // including the chat message and (silently, no visible symptom) the Merchant rebate check.
        int filled = result.filled();
        if (filled <= 0) {
            player.sendSystemMessage(Component.literal("Out of stock."));
            return false;
        }
        if (!com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge.withdrawNuggets(player, result.nuggetsCharged())) {
            // Stock was already drained/paid into the shop's own boxes above -- this shouldn't
            // normally happen since the buyer presumably has the nuggets to click "Buy" at all, but
            // if it does, refund isn't attempted here (a known, flagged simplification -- see
            // decisions.md) since under-stocked/under-funded purchases are expected to be rare.
            player.sendSystemMessage(Component.literal("You don't have enough Gold Nuggets."));
            return false;
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
        if (com.github.cerealklla.settlemynts.bridge.LyfeMerchantBridge.isLoaded()) {
            int pricePerUnit = result.nuggetsCharged() / filled;
            double fraction = com.github.cerealklla.settlemynts.bridge.LyfeMerchantBridge.getPriceBonusFraction(player);
            int effectiveCostPerUnit = com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge.effectiveBuyCost(pricePerUnit, fraction);
            rebate = Math.max(0, result.nuggetsCharged() - effectiveCostPerUnit * filled);
        }
        if (rebate > 0) {
            net.minecraft.world.item.ItemStack reward = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GOLD_NUGGET, rebate);
            if (!player.getInventory().add(reward)) {
                player.drop(reward, false);
            }
        }
        // Settlement sales tax (explicit user spec, 2026-10-17) -- the Shop itself is the Seller in
        // this transaction, so the tax comes straight out of its own boxes (which just received
        // nuggetsCharged above) and moves to the Town Hall, before the gold-total UI refresh below
        // so it reflects the post-tax amount immediately. See #resolveSalesTax's own doc for why
        // Guardhouses are naturally exempt with no special-casing needed here.
        // Temporary instrumentation (2026-10-21) -- real report: "bought a ton of wheat, no ledger
        // entry at all showed up, not even the base Sold line, on either side." Every code path here
        // was re-read and looks correct on paper, so this logs the real values instead of guessing
        // further -- remove once the live repro confirms (or disproves) the suspect branch.
        LOGGER.info("[TAX-DEBUG] processBuyLine: owner={} filled={} nuggetsCharged={}",
                shopContext.owner().getClass().getSimpleName(), filled, result.nuggetsCharged());
        if (shopContext.owner() instanceof GhostTownHallCoreEntity taxCore) {
            int tax = resolveSalesTax(taxCore, filled, result.nuggetsCharged());
            LOGGER.info("[TAX-DEBUG] taxPerItem={} townHallPlotPresent={} resolvedTax={}",
                    taxCore.taxPerItem(), taxCore.townHallPlot().isPresent(), tax);
            if (tax > 0) {
                com.github.cerealklla.settlemynts.zone.ShopResource goldNuggets = com.github.cerealklla.settlemynts.zone.ShopResource.ofItem(
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(net.minecraft.world.item.Items.GOLD_NUGGET));
                java.util.List<net.minecraft.world.item.ItemStack> withdrawnTax =
                        com.github.cerealklla.settlemynts.zone.ContainerWithdraw.drain(boxes, goldNuggets, tax);
                int actuallyTaxed = withdrawnTax.stream().mapToInt(net.minecraft.world.item.ItemStack::getCount).sum();
                LOGGER.info("[TAX-DEBUG] withdrawnFromShopBoxes={} (wanted {})", actuallyTaxed, tax);
                depositSalesTax(serverLevel, taxCore, shopContext.plot(), actuallyTaxed, player.getGameProfile().name());
            }
        }
        player.sendSystemMessage(Component.literal("Bought " + filled + " for " + result.nuggetsCharged() + " nuggets."));
        // Ledger entry (2026-10-12) -- recorded from the shop's own side, since the ledger lives on
        // this plot's Building Supply Box. Resolved live via constructionBoxId (2026-10-16 fix,
        // real standing bug: the old plot.boxPos() snapshot goes stale the moment a box is ever
        // relocated -- see BlueprintsConstructionBridge#resolveLivePos's own doc) instead of the
        // temporary diagnostic this used to fall back to.
        if (ModList.get().isLoaded("blueprynts")) {
            java.util.Optional<net.minecraft.core.BlockPos> livePos =
                    com.github.cerealklla.settlemynts.bridge.BlueprintsConstructionBridge.resolveLivePos(serverLevel, shopContext.plot());
            LOGGER.info("[TAX-DEBUG] plotId={} constructionBoxId={} resolvedLedgerBoxPos={}",
                    shopContext.plot().plotId(), shopContext.plot().constructionBoxId(), livePos);
            com.github.cerealklla.settlemynts.bridge.BlueprintsConstructionBridge.appendLedgerEntry(
                    serverLevel, shopContext.plot(),
                    "Sold " + filled + "x to " + player.getGameProfile().name() + " for " + result.nuggetsCharged() + " nuggets");
        }
        return true;
    }

    /** The once-per-batch tail of a buy (or a batch of buys) -- see {@link #processBuyLine}'s own doc. */
    private static void finishBuyBatch(ServerPlayer player, ServerLevel serverLevel, UUID plotId, UUID shopId) {
        sendShopGoldUpdate(player, serverLevel, plotId);
        sendShopStockUpdate(player, serverLevel, plotId, shopId);
        // 2026-10-10 redesign: check this one shop for auto-crafting opportunities once it's gone
        // quiet, instead of a continuous whole-world ticker -- see ShopCraftingDebounceTicker's own doc.
        com.github.cerealklla.settlemynts.zone.ShopCraftingDebounceTicker.markActivity(serverLevel, plotId);
        com.github.cerealklla.settlemynts.zone.ShopWishlistCache.invalidate(plotId);
    }

    /**
     * Pushes a fresh {@code ShopGoldUpdatePayload} so an already-open Shop screen's "Merchant Gold" /
     * "Player Gold" totals actually reflect a just-completed Buy/Sell (real report, 2026-10-10) --
     * recomputes both exactly the way {@code handleRequestShop} does when first opening the screen.
     */
    private static void sendShopGoldUpdate(ServerPlayer player, ServerLevel level, UUID plotId) {
        java.util.Map<net.minecraft.resources.Identifier, Integer> stock =
                com.github.cerealklla.settlemynts.api.Settlemynts.scanPlotItemStock(level, plotId);
        int shopGoldNuggets = stock.getOrDefault(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(net.minecraft.world.item.Items.GOLD_NUGGET), 0);
        int playerGoldNuggets = com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge.isAvailable()
                ? com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge.getNuggetBalance(player)
                : 0;
        PacketDistributor.sendToPlayer(player, new com.github.cerealklla.settlemynts.plotsign.ShopGoldUpdatePayload(shopGoldNuggets, playerGoldNuggets));
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
                : payload.customName().isPresent()
                    ? com.github.cerealklla.settlemynts.zone.ShopResource.ofExactItem(payload.resourceKey(), payload.customName().get())
                    : com.github.cerealklla.settlemynts.zone.ShopResource.ofItem(payload.resourceKey());
        // Real report, 2026-10-17: a sold item could silently arrive in the shop's box with its real
        // identity stripped (a Research Note losing which recipe target it taught, reading as a
        // blank, unrecognizable default note) -- root cause was api.Yconomics#sellToShop's old
        // plain-id+quantity deposit, which reconstructs a brand-new blank stack from just the
        // registered item type, discarding every component. Fixed at the source (see that method's
        // own doc) by passing the real matched stacks through end to end instead of a bare id+count --
        // this loop now collects real ItemStack copies (preserving components) alongside the slot
        // indices, capped at the requested quantity.
        net.minecraft.world.entity.player.Inventory inventory = player.getInventory();
        int remainingWanted = payload.quantity();
        java.util.List<Integer> matchingSlots = new java.util.ArrayList<>();
        java.util.List<net.minecraft.world.item.ItemStack> soldStacks = new java.util.ArrayList<>();
        for (int slot = 0; slot < inventory.getContainerSize() && remainingWanted > 0; slot++) {
            net.minecraft.world.item.ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !resource.matches(stack)) {
                continue;
            }
            int take = Math.min(stack.getCount(), remainingWanted);
            soldStacks.add(stack.copyWithCount(take));
            matchingSlots.add(slot);
            remainingWanted -= take;
        }
        if (soldStacks.isEmpty()) {
            player.sendSystemMessage(Component.literal("You don't have any of that to sell."));
            return;
        }
        // Real request, 2026-10-20: "need to prevent player selling their Recallcinite totem or coin
        // purse." The new ShopCartMenu's Sell lane already refuses these at the slot level (see
        // plotsign.ShopCartMenu.SellSlot); this older inventory-scanning path needs its own check
        // since it never goes through that menu at all. See plotsign.UnsellableItems's own doc.
        if (soldStacks.stream().anyMatch(com.github.cerealklla.settlemynts.plotsign.UnsellableItems::isUnsellable)) {
            player.sendSystemMessage(Component.literal("You can't sell that."));
            return;
        }
        int itemsSold = processSellLine(player, serverLevel, resolvedSell.get(), shopId.get(), resource, soldStacks);
        if (itemsSold <= 0) {
            return;
        }
        // All-or-nothing on the Yconomics side (see processSellLine's own doc) -- itemsSold is always
        // either 0 or exactly sum(soldStacks counts), so removing that many in the same slot order
        // soldStacks was built from stays consistent with whichever exact stacks were just deposited.
        int remaining = itemsSold;
        for (int slot : matchingSlots) {
            if (remaining <= 0) {
                break;
            }
            int take = Math.min(remaining, inventory.getItem(slot).getCount());
            inventory.removeItem(slot, take);
            remaining -= take;
        }
        finishBuyBatch(player, serverLevel, plotId, shopId.get());
    }

    /**
     * Refactored out of the old inventory-sourced {@link #sellToShop} 2026-10-20 -- same "resolve
     * once, loop many" motivation as {@link #processBuyLine}'s own doc, but ALSO reused for a
     * genuinely different stack *source*: the old caller scans the player's live inventory for
     * matching stacks, while {@link #confirmShopCart} (the new cart-screen batch path) already has
     * its {@code soldStacks} sitting in the cart menu's own Sell container, never in the player's
     * inventory at all. This method deliberately doesn't know or care which -- it just processes
     * already-resolved {@code soldStacks} into a real transaction (deposit into the shop, pay the
     * seller, tax, Merchant bonus/XP, message, ledger) and returns how many units were actually
     * sold (0 if the shop couldn't afford it) -- the caller is responsible for removing exactly that
     * many units from wherever {@code soldStacks} actually came from, since Yconomics' own "all-or-
     * nothing" guarantee (itemsSold is always 0 or exactly {@code sum(soldStacks counts)}, see the
     * original method's own comment) means that's always safe to do unconditionally once this
     * returns a positive count.
     */
    private static int processSellLine(ServerPlayer player, ServerLevel serverLevel, ShopContext shopContext, UUID shopId,
                                        com.github.cerealklla.settlemynts.zone.ShopResource resource,
                                        java.util.List<net.minecraft.world.item.ItemStack> soldStacks) {
        com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge.SellResult result =
                com.github.cerealklla.settlemynts.api.Settlemynts.sellToSettlementShop(serverLevel, shopContext.plot().plotId(), shopId, resource, soldStacks);
        if (result.itemsSold() <= 0) {
            player.sendSystemMessage(Component.literal("The shop can't afford to buy that right now."));
            return 0;
        }
        int nuggetsOwed = result.nuggetsReceived();
        // Merchant skill price bonus, sell-side (2026-10-08) -- same mechanism/formula as
        // MerchantListener's own vanilla-NPC sell bonus: extra nuggets paid directly, since there's
        // no per-offer field to boost a fixed result (see that class's own doc for why). Computed
        // per-unit via ShopPricing.effectiveSellPayout (not a flat % bonus on top of the base payout)
        // -- that method guarantees the result is always strictly less than what the SAME player
        // would currently pay to buy this item back, closing a real zero-cost buy/sell XP loop a
        // naive flat-% bonus here would otherwise allow (see processBuyLine's own comment for the
        // exact rounding case found).
        if (com.github.cerealklla.settlemynts.bridge.LyfeMerchantBridge.isLoaded()) {
            double fraction = com.github.cerealklla.settlemynts.bridge.LyfeMerchantBridge.getPriceBonusFraction(player);
            int effectivePayoutPerUnit = com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge.effectiveSellPayout(result.sellPricePerUnit(), fraction);
            nuggetsOwed = effectivePayoutPerUnit * result.itemsSold();
        }
        // Settlement sales tax (explicit user spec, 2026-10-17) -- the player is the Seller in this
        // transaction, withheld straight out of their payout before it's ever minted/handed over (no
        // withdrawal needed, unlike buyFromShop's case, since this gold was never given to anyone yet).
        if (shopContext.owner() instanceof GhostTownHallCoreEntity taxCore) {
            int tax = resolveSalesTax(taxCore, result.itemsSold(), nuggetsOwed);
            if (tax > 0) {
                nuggetsOwed -= tax;
                depositSalesTax(serverLevel, taxCore, null, tax, player.getGameProfile().name());
            }
        }
        net.minecraft.world.item.ItemStack payment = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GOLD_NUGGET, nuggetsOwed);
        if (!player.getInventory().add(payment)) {
            player.drop(payment, false);
        }
        if (com.github.cerealklla.settlemynts.bridge.LyfeMerchantBridge.isLoaded()) {
            com.github.cerealklla.settlemynts.bridge.LyfeMerchantBridge.grantMerchantXp(player, nuggetsOwed);
        }
        player.sendSystemMessage(Component.literal("Sold " + result.itemsSold() + " for " + nuggetsOwed + " nuggets."));
        // Ledger entry (2026-10-12) -- see processBuyLine's own identical hook/fix.
        if (ModList.get().isLoaded("blueprynts")) {
            com.github.cerealklla.settlemynts.bridge.BlueprintsConstructionBridge.appendLedgerEntry(
                    serverLevel, shopContext.plot(),
                    "Bought " + result.itemsSold() + "x from " + player.getGameProfile().name() + " for " + nuggetsOwed + " nuggets");
        }
        return result.itemsSold();
    }

    /**
     * "Confirm Purchase" on the new cart-screen {@code plotsign.ShopCartMenu} (2026-10-20) --
     * resolves the shop ONCE, then processes every distinct listing with something queued in Buy/
     * Sell against that same resolution (see {@link #processBuyLine}/{@link #processSellLine}'s own
     * docs for why this avoids the full-world-scan-per-line cost a naive per-slot loop would have).
     * Grouped by matching {@link ShopListingEntry#matches}, not by slot position, via {@code
     * zone.ContainerWithdraw#drain} against the menu's own Buy/Sell containers -- a player is free to
     * rearrange/merge stacks within either column without this caring where exactly they ended up.
     *
     * <p>Buy lines are drained from the cart unconditionally (conjured stacks have no real backing,
     * so there's nothing to lose if a line fails -- "out of stock"/"insufficient funds" still reaches
     * the player via the usual chat message from {@link #processBuyLine}). Sell lines are different:
     * {@link com.github.cerealklla.nuclyus.trade.TradeMenuSupport} drain removes real items from the
     * menu's Sell container as a side effect, so a failed sell line (the shop can't afford it) puts
     * those real stacks straight back rather than letting them vanish.
     */
    public static void confirmShopCart(ServerPlayer player, com.github.cerealklla.settlemynts.plotsign.ShopCartMenu menu) {
        if (!(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        java.util.Optional<ShopContext> resolved = resolveShopContext(serverLevel, menu.anchor());
        if (resolved.isEmpty()) {
            player.sendSystemMessage(Component.literal("Couldn't resolve this plot anymore."));
            return;
        }
        java.util.Optional<UUID> shopId = resolved.get().plot().shopId();
        if (shopId.isEmpty()) {
            return;
        }
        java.util.List<net.minecraft.world.Container> boxes =
                com.github.cerealklla.settlemynts.api.Settlemynts.resolvePlotBoxes(serverLevel, resolved.get().plot().plotId());
        // Freshly re-resolved (2026-10-20), not resolved.get().plot() -- plannedInventory() can have
        // changed (another player's Manage Shop edit, or simply drifted stock) since this menu was
        // opened, and the cap below must be authoritative, not a stale snapshot. See ShopListingEntry's
        // own doc for why this restriction exists at all.
        java.util.Optional<PlotRecord> freshPlot =
                com.github.cerealklla.settlemynts.api.Settlemynts.findPlotRecord(serverLevel, resolved.get().plot().plotId());

        boolean anythingProcessed = false;
        boolean anythingReturnedUnwanted = false;
        for (com.github.cerealklla.settlemynts.plotsign.ShopListingEntry listing : menu.listings()) {
            com.github.cerealklla.settlemynts.zone.ShopResource resource = resourceFor(listing);

            java.util.List<net.minecraft.world.item.ItemStack> queuedBuy =
                    com.github.cerealklla.settlemynts.zone.ContainerWithdraw.drain(java.util.List.of(menu.buyContainer()), resource, Integer.MAX_VALUE);
            int buyQuantity = queuedBuy.stream().mapToInt(net.minecraft.world.item.ItemStack::getCount).sum();
            if (buyQuantity > 0) {
                anythingProcessed = true;
                processBuyLine(player, serverLevel, resolved.get(), boxes, shopId.get(), resource, buyQuantity);
            }

            java.util.List<net.minecraft.world.item.ItemStack> queuedSell =
                    com.github.cerealklla.settlemynts.zone.ContainerWithdraw.drain(java.util.List.of(menu.sellContainer()), resource, Integer.MAX_VALUE);
            if (queuedSell.isEmpty()) {
                continue;
            }
            anythingProcessed = true;
            // Real report, 2026-10-20: "this rework is allowing a player to sell something to a shop
            // that they don't want to buy" / "the UI will permit the player to over-stock the planned
            // inventory for that particular item" -- ShopCartMenu.SellSlot already keeps a wholly
            // un-wanted resource out of the Sell column in the first place, but can't cheaply enforce
            // an exact running total across several slots/clicks, so the authoritative cap is enforced
            // here instead: never sell more than the plot's own PlannedInventoryTarget still wants,
            // recomputed fresh against live stock right now.
            int cap = freshPlot.flatMap(p -> findPlannedInventoryTarget(p, listing.resourceKey(), listing.isTag()))
                    .map(target -> Math.max(0, target.targetCount() - com.github.cerealklla.settlemynts.zone.ContainerWithdraw.countAvailable(boxes, resource)))
                    .orElse(0);
            int queuedCount = queuedSell.stream().mapToInt(net.minecraft.world.item.ItemStack::getCount).sum();
            java.util.List<net.minecraft.world.item.ItemStack> toSell = new java.util.ArrayList<>();
            java.util.List<net.minecraft.world.item.ItemStack> toReturn = new java.util.ArrayList<>();
            int remainingCap = cap;
            for (net.minecraft.world.item.ItemStack stack : queuedSell) {
                if (remainingCap <= 0) {
                    toReturn.add(stack);
                    continue;
                }
                if (stack.getCount() <= remainingCap) {
                    toSell.add(stack);
                    remainingCap -= stack.getCount();
                } else {
                    toSell.add(stack.copyWithCount(remainingCap));
                    toReturn.add(stack.copyWithCount(stack.getCount() - remainingCap));
                    remainingCap = 0;
                }
            }
            if (toReturn.stream().mapToInt(net.minecraft.world.item.ItemStack::getCount).sum() > 0) {
                anythingReturnedUnwanted = true;
                for (net.minecraft.world.item.ItemStack stack : toReturn) {
                    if (!player.getInventory().add(stack)) {
                        player.getInventory().placeItemBackInInventory(stack);
                    }
                }
            }
            if (toSell.isEmpty()) {
                continue;
            }
            int sold = processSellLine(player, serverLevel, resolved.get(), shopId.get(), resource, toSell);
            if (sold <= 0) {
                // Nothing sold (shop couldn't afford it) -- the drain above already pulled these real
                // stacks out of the Sell container; give them straight back rather than losing them.
                for (net.minecraft.world.item.ItemStack stack : toSell) {
                    if (!player.getInventory().add(stack)) {
                        player.getInventory().placeItemBackInInventory(stack);
                    }
                }
            }
        }
        if (anythingReturnedUnwanted) {
            player.sendSystemMessage(Component.literal("The shop didn't need that much -- returned what it didn't want."));
        }
        if (!anythingProcessed) {
            player.sendSystemMessage(Component.literal("Nothing queued to buy or sell."));
            return;
        }
        finishBuyBatch(player, serverLevel, resolved.get().plot().plotId(), shopId.get());
    }

    /**
     * "Auto Sell" on the cart screen (2026-10-20, explicit user request) -- queues everything the
     * player is carrying that the shop's Planned Inventory currently wants into the Sell column, for
     * the player to then Confirm Purchase. Scans the player's real main inventory/hotbar only (vanilla's
     * own {@code Inventory} backs {@code Container#getItem}/{@code #removeItem} with exactly those 36
     * slots -- armor/offhand live in a separate {@code EntityEquipment}, confirmed against the
     * decompiled source, so this can never sweep up worn gear). Caps per resource at the same live
     * {@code targetCount - currentStock} formula {@link #confirmShopCart} itself enforces, minus
     * whatever's already sitting in the Sell column from an earlier click, so repeated clicks are safe.
     */
    public static void autoSellToShopCart(ServerPlayer player, com.github.cerealklla.settlemynts.plotsign.ShopCartMenu menu) {
        if (!(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        java.util.Optional<ShopContext> resolved = resolveShopContext(serverLevel, menu.anchor());
        if (resolved.isEmpty()) {
            player.sendSystemMessage(Component.literal("Couldn't resolve this plot anymore."));
            return;
        }
        java.util.Optional<PlotRecord> freshPlot =
                com.github.cerealklla.settlemynts.api.Settlemynts.findPlotRecord(serverLevel, resolved.get().plot().plotId());
        if (freshPlot.isEmpty()) {
            return;
        }
        java.util.List<net.minecraft.world.Container> boxes =
                com.github.cerealklla.settlemynts.api.Settlemynts.resolvePlotBoxes(serverLevel, resolved.get().plot().plotId());

        boolean queuedAnything = false;
        for (com.github.cerealklla.settlemynts.plotsign.ShopListingEntry listing : menu.listings()) {
            if (listing.plannedInventoryRemaining() <= 0) {
                continue;
            }
            com.github.cerealklla.settlemynts.zone.ShopResource resource = resourceFor(listing);
            int remaining = findPlannedInventoryTarget(freshPlot.get(), listing.resourceKey(), listing.isTag())
                    .map(target -> Math.max(0, target.targetCount() - com.github.cerealklla.settlemynts.zone.ContainerWithdraw.countAvailable(boxes, resource)))
                    .orElse(0);
            remaining -= com.github.cerealklla.settlemynts.zone.ContainerWithdraw.countAvailable(java.util.List.of(menu.sellContainer()), resource);
            if (remaining <= 0) {
                continue;
            }
            java.util.List<net.minecraft.world.item.ItemStack> drained =
                    com.github.cerealklla.settlemynts.zone.ContainerWithdraw.drain(java.util.List.of(player.getInventory()), resource, remaining);
            for (net.minecraft.world.item.ItemStack stack : drained) {
                com.github.cerealklla.settlemynts.zone.ContainerDeposit.depositIntoAny(java.util.List.of(menu.sellContainer()), stack);
                if (!stack.isEmpty()) {
                    // The Sell column's limited slots ran out of room -- give back whatever didn't fit.
                    if (!player.getInventory().add(stack)) {
                        player.getInventory().placeItemBackInInventory(stack);
                    }
                }
            }
            queuedAnything = queuedAnything || !drained.isEmpty();
        }
        player.sendSystemMessage(Component.literal(queuedAnything
                ? "Auto Sell: queued everything the shop currently wants that you're carrying."
                : "Nothing to auto-sell -- the shop doesn't want more of anything you're carrying right now."));
    }

    /**
     * "Press &lt;key&gt; to chat" -- the public wishlist request (2026-10-10, see {@code
     * zone.ShopWishlist}'s own doc). Any player, not just the plot's own owner, can ask -- that's the
     * whole point ("public wishlist").
     */
    private static void requestShopWishlist(com.github.cerealklla.settlemynts.plotsign.RequestShopWishlistPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        handleRequestShopWishlist(player, serverLevel, payload.anchor());
    }

    /**
     * Real entry point behind {@link #requestShopWishlist} -- pulled out standalone 2026-10-12 so
     * {@code resident.VillagerShopInteractListener} can trigger the exact same "Press H" chat menu
     * directly from a right-click, not just the keybind's network round-trip. Real report, same day:
     * right-clicking the Town Hall plot's own NPC-owned resident did nothing at all, unlike every
     * other plot NPC -- that listener's right-click handling only ever opened a Shop
     * ({@code npcHasOpenableShop}), with no fallback for an NPC (like the Town Hall's) that can never
     * have one by design but can still have a quest-board offer to talk about.
     */
    public static void handleRequestShopWishlist(ServerPlayer player, ServerLevel serverLevel, com.github.cerealklla.settlemynts.plotsign.ShopAnchor anchor) {
        java.util.Optional<ShopContext> resolved = resolveShopContext(serverLevel, anchor);
        if (resolved.isEmpty()) {
            player.sendSystemMessage(Component.literal("Couldn't resolve this plot anymore."));
            return;
        }
        PlotRecord plot = resolved.get().plot();
        PacketDistributor.sendToPlayer(player, new com.github.cerealklla.settlemynts.plotsign.OpenShopWishlistPayload(
                anchor, buildWishlistEntries(serverLevel, plot, resolved.get().anchorPos()),
                buildQuestMenu(serverLevel, player, plot)));
    }

    /**
     * "I've got a job that needs doing" -> the board's new offer -> "Accept Quest" on {@code
     * client.QuestAcceptScreen} (2026-10-10). See {@code bridge.MyssionsQuestBridge#acceptBoardOffer}'s
     * own doc.
     */
    private static void acceptBoardOffer(com.github.cerealklla.settlemynts.plotsign.AcceptBoardOfferPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        java.util.Optional<ShopContext> resolved = resolveShopContext(serverLevel, payload.anchor());
        if (resolved.isEmpty()) {
            player.sendSystemMessage(Component.literal("Couldn't resolve this plot anymore."));
            return;
        }
        PlotRecord plot = resolved.get().plot();
        if (com.github.cerealklla.settlemynts.bridge.MyssionsQuestBridge.isLoaded()) {
            com.github.cerealklla.settlemynts.bridge.MyssionsQuestBridge.acceptBoardOffer(player, plot.plotId(), payload.questId());
        }
        // Real report, 2026-10-12: accepting the plot's last offer left its NPC still showing a
        // stale "?" nameplate, since nothing clears it except a future proximity re-scan -- see
        // plotsign.PlotShopProximityTicker#refreshNameplate's own doc.
        if (payload.anchor() instanceof com.github.cerealklla.settlemynts.plotsign.ShopAnchor.Npc npcAnchor) {
            com.github.cerealklla.settlemynts.plotsign.PlotShopProximityTicker.refreshNameplate(serverLevel, npcAnchor);
        }
        PacketDistributor.sendToPlayer(player, new com.github.cerealklla.settlemynts.plotsign.OpenShopWishlistPayload(
                payload.anchor(), buildWishlistEntries(serverLevel, plot, resolved.get().anchorPos()),
                buildQuestMenu(serverLevel, player, plot)));
    }

    /**
     * "Establish Highway: <destination>" -> "Accept Quest" on {@code client.RoadQuestAcceptScreen}
     * -- added 2026-10-13. Re-resolves this Town Hall's own settlement placeId from the anchor
     * itself (never trusts the client) before calling Myssions' already-built {@code
     * quest.RoadQuestLogic#acceptOffer} via {@code bridge.MyssionsQuestBridge#acceptRoadQuestOffer}.
     */
    private static void acceptRoadQuestOffer(com.github.cerealklla.settlemynts.plotsign.AcceptRoadQuestOfferPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        java.util.Optional<ShopContext> resolved = resolveShopContext(serverLevel, payload.anchor());
        if (resolved.isEmpty()) {
            player.sendSystemMessage(Component.literal("Couldn't resolve this plot anymore."));
            return;
        }
        PlotRecord plot = resolved.get().plot();
        if (!com.github.cerealklla.settlemynts.bridge.MyssionsQuestBridge.isLoaded() || plot.boxPos().isEmpty()) {
            return;
        }
        java.util.Optional<com.github.cerealklla.settlemynts.api.Settlemynts.PlotHandle> handle =
                com.github.cerealklla.settlemynts.api.Settlemynts.findPlotAt(serverLevel, plot.boxPos().get());
        if (handle.isEmpty() || !(serverLevel.getEntity(handle.get().settlementCoreId()) instanceof GhostTownHallCoreEntity core)
                || core.getCartographyrEntityId() == null) {
            player.sendSystemMessage(Component.literal("Couldn't resolve this settlement anymore."));
            return;
        }
        boolean accepted = com.github.cerealklla.settlemynts.bridge.MyssionsQuestBridge.acceptRoadQuestOffer(
                player, core.getCartographyrEntityId(), payload.destinationPlaceId());
        if (!accepted) {
            player.sendSystemMessage(Component.literal("Couldn't accept that quest -- it may have already been taken."));
            return;
        }
        PacketDistributor.sendToPlayer(player, new com.github.cerealklla.settlemynts.plotsign.OpenShopWishlistPayload(
                payload.anchor(), buildWishlistEntries(serverLevel, plot, resolved.get().anchorPos()),
                buildQuestMenu(serverLevel, player, plot)));
    }

    /**
     * "I've got a job that needs doing" -> pick an active quest by name -> "Complete Quest" on
     * {@code client.QuestCompleteScreen} (2026-10-10, explicit user spec: "a sub-menu [with] the
     * option to complete the quest"). See {@code bridge.MyssionsQuestBridge#completeQuest}'s own doc.
     */
    private static void completeQuest(com.github.cerealklla.settlemynts.plotsign.CompleteQuestPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        java.util.Optional<ShopContext> resolved = resolveShopContext(serverLevel, payload.anchor());
        if (resolved.isEmpty()) {
            player.sendSystemMessage(Component.literal("Couldn't resolve this plot anymore."));
            return;
        }
        PlotRecord plot = resolved.get().plot();
        boolean openedRewardMenu = false;
        if (com.github.cerealklla.settlemynts.bridge.MyssionsQuestBridge.isLoaded()) {
            openedRewardMenu = com.github.cerealklla.settlemynts.bridge.MyssionsQuestBridge.completeQuest(player, payload.instanceId());
        }
        // A reward GUI just opened via a plain vanilla container-open packet -- must NOT send a
        // chat-menu refresh right behind it, or the client would immediately override/close that
        // screen with ChatMenuScreen again (real bug found live, 2026-10-11). Only still-incomplete
        // (false) genuinely needs the usual refresh, so QuestCompleteScreen reflects the latest state.
        if (openedRewardMenu) {
            return;
        }
        PacketDistributor.sendToPlayer(player, new com.github.cerealklla.settlemynts.plotsign.OpenShopWishlistPayload(
                payload.anchor(), buildWishlistEntries(serverLevel, plot, resolved.get().anchorPos()),
                buildQuestMenu(serverLevel, player, plot)));
    }

    /**
     * Per-player quest-giver menu for {@link #requestShopWishlist}/{@link #sellToShopWishlist}/
     * {@link #acceptBoardOffer}/{@link #completeQuest}'s own payload -- empty if Myssions isn't
     * loaded. For a Town Hall plot specifically, also folds in any not-yet-accepted Establish Highway
     * offer the settlement has posted out (added 2026-10-13) -- these never go through {@code
     * IssuerQuestBoard}, so {@code bridge.MyssionsQuestBridge#getMenu}'s own board-offer scan would
     * never find them on its own.
     */
    private static com.github.cerealklla.settlemynts.plotsign.QuestMenuInfo buildQuestMenu(ServerLevel level, ServerPlayer player, PlotRecord plot) {
        if (!com.github.cerealklla.settlemynts.bridge.MyssionsQuestBridge.isLoaded()) {
            return com.github.cerealklla.settlemynts.plotsign.QuestMenuInfo.EMPTY;
        }
        com.github.cerealklla.settlemynts.plotsign.QuestMenuInfo menu =
                com.github.cerealklla.settlemynts.bridge.MyssionsQuestBridge.getMenu(player, plot.plotId(), plot.zoneTypeId().toString());
        if (!plot.zoneTypeId().equals(GhostTownHallCoreEntity.TOWN_HALL_ZONE_TYPE_ID) || plot.boxPos().isEmpty()) {
            return menu;
        }
        java.util.Optional<com.github.cerealklla.settlemynts.api.Settlemynts.PlotHandle> handle =
                com.github.cerealklla.settlemynts.api.Settlemynts.findPlotAt(level, plot.boxPos().get());
        if (handle.isEmpty() || !(level.getEntity(handle.get().settlementCoreId()) instanceof GhostTownHallCoreEntity core)
                || core.getCartographyrEntityId() == null) {
            return menu;
        }
        java.util.List<com.github.cerealklla.settlemynts.plotsign.QuestMenuInfo.RoadQuestOffer> roadQuestOffers = new java.util.ArrayList<>();
        for (var offer : com.github.cerealklla.settlemynts.bridge.MyssionsQuestBridge.getPostedRoadQuestOffers(level, core.getCartographyrEntityId())) {
            roadQuestOffers.add(new com.github.cerealklla.settlemynts.plotsign.QuestMenuInfo.RoadQuestOffer(offer.destinationPlaceId(), offer.destinationName()));
        }
        return new com.github.cerealklla.settlemynts.plotsign.QuestMenuInfo(menu.activeQuests(), menu.newOffers(), roadQuestOffers);
    }

    /** Shared row-assembly for {@link #requestShopWishlist} and {@link #sellToShopWishlist}'s own post-sale refresh. */
    private static java.util.List<com.github.cerealklla.settlemynts.plotsign.WishlistEntry> buildWishlistEntries(
            ServerLevel level, PlotRecord plot, net.minecraft.core.BlockPos anchorPos) {
        java.util.List<com.github.cerealklla.settlemynts.plotsign.WishlistEntry> entries = new java.util.ArrayList<>();
        for (com.github.cerealklla.settlemynts.zone.ShopWishlist.DeficitEntry deficit :
                com.github.cerealklla.settlemynts.zone.ShopWishlist.computeDeficits(level, plot)) {
            com.github.cerealklla.settlemynts.zone.ShopResource resource = deficit.resource();
            int normalPrice = com.github.cerealklla.settlemynts.zone.ShopWishlist.normalPriceFor(level, plot, anchorPos, resource);
            net.minecraft.resources.Identifier key = resource.itemId().orElseGet(() -> resource.tag().orElseThrow().location());
            entries.add(new com.github.cerealklla.settlemynts.plotsign.WishlistEntry(
                    key, resource.tag().isPresent(), deficit.quantityNeeded(), normalPrice,
                    com.github.cerealklla.settlemynts.zone.ShopWishlist.premiumPrice(normalPrice)));
        }
        entries.sort(java.util.Comparator.comparing(com.github.cerealklla.settlemynts.plotsign.WishlistEntry::label));
        return entries;
    }

    /**
     * "Sell" on one {@code client.WishlistScreen} row -- a direct, premium-priced sale against the
     * shop's own Planned Inventory deficit (see {@code zone.ShopWishlist}'s own doc). No separate
     * "ask first" gate any more (removed 2026-10-10, explicit user feedback: "the 'ask what he wants'
     * blocking repeated selling is tedious" -- the real-time deficit/price recompute below is the
     * actual anti-exploit guard; a time-limited offer on top of that was just friction, not safety).
     * The deficit, player stock, and shop's own gold are all re-resolved live here rather than
     * trusting anything the client remembered from when it last asked.
     */
    private static void sellToShopWishlist(com.github.cerealklla.settlemynts.plotsign.SellToShopWishlistPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        java.util.Optional<ShopContext> resolved = resolveShopContext(serverLevel, payload.anchor());
        if (resolved.isEmpty()) {
            player.sendSystemMessage(Component.literal("Couldn't resolve this plot anymore."));
            return;
        }
        PlotRecord plot = resolved.get().plot();
        com.github.cerealklla.settlemynts.zone.ShopResource resource = payload.isTag()
                ? com.github.cerealklla.settlemynts.zone.ShopResource.ofTag(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, payload.resourceKey()))
                : com.github.cerealklla.settlemynts.zone.ShopResource.ofItem(payload.resourceKey());

        // Re-resolved from ShopWishlist itself, not a raw PlannedInventoryTarget lookup (real bug,
        // 2026-10-10: "I tried to sell an item to the shop that it was asking for and it said it
        // didn't want it right now") -- a translated material (e.g. the Armorer's own Iron Ingot
        // need, derived live from its Armor recipe) never has its own PlannedInventoryTarget at all,
        // only the finished good it's translated FROM does, so a raw target lookup here always came
        // up empty for exactly the resources this screen actually offers. ShopWishlist is the single
        // authoritative source for "how much is actually still needed" -- same one requestShopWishlist
        // itself used to build this screen in the first place.
        java.util.List<net.minecraft.world.Container> boxes = com.github.cerealklla.settlemynts.api.Settlemynts.resolvePlotBoxes(serverLevel, plot.plotId());
        int remainingDeficit = com.github.cerealklla.settlemynts.zone.ShopWishlist.computeDeficits(serverLevel, plot).stream()
                .filter(d -> d.resource().tag().isPresent() == payload.isTag()
                        && (payload.isTag()
                                ? d.resource().tag().get().location().equals(payload.resourceKey())
                                : d.resource().itemId().get().equals(payload.resourceKey())))
                .findFirst()
                .map(com.github.cerealklla.settlemynts.zone.ShopWishlist.DeficitEntry::quantityNeeded)
                .orElse(0);
        if (remainingDeficit <= 0) {
            player.sendSystemMessage(Component.literal("This shop doesn't need any more of that right now."));
            return;
        }

        net.minecraft.world.entity.player.Inventory inventory = player.getInventory();
        int playerStock = 0;
        java.util.List<Integer> matchingSlots = new java.util.ArrayList<>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            net.minecraft.world.item.ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !resource.matches(stack)) {
                continue;
            }
            playerStock += stack.getCount();
            matchingSlots.add(slot);
        }
        if (playerStock <= 0) {
            player.sendSystemMessage(Component.literal("You don't have any of that to sell."));
            return;
        }

        int premiumPrice = com.github.cerealklla.settlemynts.zone.ShopWishlist.premiumPrice(
                com.github.cerealklla.settlemynts.zone.ShopWishlist.normalPriceFor(serverLevel, plot, resolved.get().anchorPos(), resource));
        com.github.cerealklla.settlemynts.zone.ShopResource goldResource = com.github.cerealklla.settlemynts.zone.ShopResource.ofItem(
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(net.minecraft.world.item.Items.GOLD_NUGGET));
        int affordable = com.github.cerealklla.settlemynts.zone.ContainerWithdraw.countAvailable(boxes, goldResource) / premiumPrice;
        int quantity = Math.min(Math.max(1, payload.quantity()), Math.min(remainingDeficit, Math.min(playerStock, affordable)));
        if (quantity <= 0) {
            player.sendSystemMessage(Component.literal("The shop can't afford to buy that right now."));
            return;
        }

        int remaining = quantity;
        for (int slot : matchingSlots) {
            if (remaining <= 0) {
                break;
            }
            int take = Math.min(remaining, inventory.getItem(slot).getCount());
            inventory.removeItem(slot, take);
            remaining -= take;
        }
        com.github.cerealklla.settlemynts.zone.ContainerDeposit.depositIntoAny(boxes,
                new net.minecraft.world.item.ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(payload.resourceKey()), quantity));
        int nuggetsOwed = quantity * premiumPrice;
        for (net.minecraft.world.item.ItemStack stack : com.github.cerealklla.settlemynts.zone.ContainerWithdraw.drain(boxes, goldResource, nuggetsOwed)) {
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
        }
        player.sendSystemMessage(Component.literal("Sold " + quantity + " for " + nuggetsOwed + " nuggets."));
        com.github.cerealklla.settlemynts.zone.ShopWishlistCache.invalidate(plot.plotId());
        // Real report, 2026-10-10: "I sold the armorer everything he wanted but he never crafted the
        // items I wanted him to craft" -- buyFromShop/sellToShop both nudge this debounce ticker so a
        // plot actually attempts crafting shortly after receiving materials, instead of waiting for
        // the next midnight tick; this handler was missing the same call entirely.
        com.github.cerealklla.settlemynts.zone.ShopCraftingDebounceTicker.markActivity(serverLevel, plot.plotId());

        sendShopGoldUpdate(player, serverLevel, plot.plotId());
        PacketDistributor.sendToPlayer(player, new com.github.cerealklla.settlemynts.plotsign.OpenShopWishlistPayload(
                payload.anchor(), buildWishlistEntries(serverLevel, plot, resolved.get().anchorPos()),
                buildQuestMenu(serverLevel, player, plot)));
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
                    : update.customName().isPresent()
                        ? com.github.cerealklla.settlemynts.zone.ShopResource.ofExactItem(update.resourceKey(), update.customName().get())
                        : com.github.cerealklla.settlemynts.zone.ShopResource.ofItem(update.resourceKey());
            com.github.cerealklla.settlemynts.zone.SuppressedShopResource marker =
                    new com.github.cerealklla.settlemynts.zone.SuppressedShopResource(update.resourceKey(), update.isTag(), update.customName());
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
        com.github.cerealklla.settlemynts.zone.ShopWishlistCache.invalidate(plot.plotId());
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
            // Guard Kit Stand overhaul (2026-10-16)/Construction Box funding rework (2026-10-22) --
            // a derived entry is fully automatic (recomputed from the plot's own stands or its
            // Construction Box's own shortfall), never shown/editable on this screen.
            if (target.isDerived()) {
                continue;
            }
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
        // Guard Kit Stand overhaul (2026-10-16)/Construction Box funding rework (2026-10-22) -- this
        // screen never shows a derived entry, so a manual save only ever submits the non-derived
        // portion; preserve every existing derived entry verbatim rather than silently dropping it.
        for (com.github.cerealklla.settlemynts.zone.PlannedInventoryTarget existing : resolved.get().plot().plannedInventory()) {
            if (existing.isDerived()) {
                targets.add(existing);
            }
        }
        resolved.get().core().updatePlot(resolved.get().plot().withPlannedInventory(targets));
        com.github.cerealklla.settlemynts.zone.ShopWishlistCache.invalidate(resolved.get().plot().plotId());
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

    /**
     * Would {@code handleRequestShop(player, anchor, false)} actually open a Shop right now -- same
     * "doesn't have a Shop" condition that method itself falls back to, exposed read-only so {@code
     * plotsign.PlotShopProximityTicker}/{@code resident.VillagerShopInteractListener} can skip
     * offering the "Press G"/right-click interaction at all for an NPC whose plot has no Shop, rather
     * than offering it and then dead-ending with "This plot doesn't have a Shop." Real report,
     * 2026-10-09: a plain {@code resident.ResidentVillagerEntity} (the ambient decorative NPC spawned
     * on an NPC-owned, shopless plot like Private Residence) already has a real {@code plotId}, so it
     * was never caught by the existing "does this NPC even have a plot" checks those two classes
     * already had -- this is the real, narrower condition both actually need.
     */
    public static boolean npcHasOpenableShop(ServerLevel level, com.github.cerealklla.settlemynts.plotsign.ShopAnchor.Npc anchor) {
        return resolveShopContext(level, anchor)
                .map(ctx -> {
                    PlotRecord plot = ctx.plot();
                    return !plot.zoneTypeId().equals(com.github.cerealklla.settlemynts.guardhouse.GuardhouseConstants.GUARDHOUSE_ZONE_TYPE_ID)
                            && !plot.zoneTypeId().equals(GhostTownHallCoreEntity.TOWN_HALL_ZONE_TYPE_ID)
                            && plot.shopId().isPresent();
                })
                .orElse(false);
    }

    /**
     * Does the NPC behind {@code anchor} currently have anything to offer through the "Press H"
     * chat menu -- either an unmet Planned Inventory deficit (see {@code zone.ShopWishlist}'s own
     * doc) or an unclaimed Myssions quest-board offer (added 2026-10-10, see {@code
     * bridge.MyssionsQuestBridge}'s own doc). Used by {@code plotsign.PlotShopProximityTicker} for
     * both the "Press &lt;key&gt; to chat" prompt and the floating "!" nameplate -- both are
     * deliberately player-independent (global) signals, which is why the quest half of this check
     * only looks at board-offer presence, not any specific player's own in-progress/ready-to-deliver
     * state (see {@code api.Myssions#hasPendingBoardOffer}'s own doc for why that distinction
     * matters).
     */
    public static boolean anchorHasWishlist(ServerLevel level, com.github.cerealklla.settlemynts.plotsign.ShopAnchor anchor) {
        return resolveShopContext(level, anchor)
                .map(ctx -> com.github.cerealklla.settlemynts.zone.ShopWishlistCache.hasWishlist(level, ctx.plot())
                        || (com.github.cerealklla.settlemynts.bridge.MyssionsQuestBridge.isLoaded()
                                && com.github.cerealklla.settlemynts.bridge.MyssionsQuestBridge.hasPendingBoardOffer(level, ctx.plot().plotId())))
                .orElse(false);
    }

    /**
     * Same as {@link #anchorHasWishlist}, plus {@code player}'s own active/awaiting-pickup quests
     * from this NPC's pool -- real bug found live, 2026-10-11: "I took a quest from an NPC but after
     * accepting the quest his 'H' menu went away... I came back... with the required proof but his
     * H menu is still gone so I can't turn it in." Root cause: the board slot clears the instant a
     * quest is accepted, so the global, player-independent {@link #anchorHasWishlist} check (which
     * only ever looks at the board/wishlist, never a specific player's own quest state) correctly
     * went back to false for everyone -- but the player who'd just accepted it still needed "Press H"
     * to turn it in later, and had no other way to reach it. Used only for the per-player "Press H to
     * chat" prompt itself ({@code plotsign.PlotShopProximityTicker}) -- the floating "!" nameplate
     * stays purely {@link #anchorHasWishlist}, since a nameplate is shared by every viewer and can't
     * represent "player X personally has unfinished business here."
     */
    public static boolean anchorHasPersonalQuestBusiness(ServerPlayer player, ServerLevel level, com.github.cerealklla.settlemynts.plotsign.ShopAnchor anchor) {
        if (!com.github.cerealklla.settlemynts.bridge.MyssionsQuestBridge.isLoaded()) {
            return false;
        }
        return resolveShopContext(level, anchor)
                .map(ctx -> {
                    PlotRecord plot = ctx.plot();
                    var menu = com.github.cerealklla.settlemynts.bridge.MyssionsQuestBridge.getMenu(player, plot.plotId(), plot.zoneTypeId().toString());
                    return !menu.activeQuests().isEmpty();
                })
                .orElse(false);
    }

    /**
     * Narrower than {@link #anchorHasPersonalQuestBusiness} -- true only when {@code player} has an
     * incomplete talk milestone or a fully ready-to-turn-in quest with this specific issuer, not just
     * "any active quest at all." Drives the issuer NPC's personal "!" head marker (2026-10-11 user
     * spec: "a ! only visible to players who have a active Milestone to talk to them, or a quest
     * ready to turn back in"), computed by {@code plotsign.PersonalQuestMarkerTicker} and rendered
     * client-side via {@code plotsign.client.PersonalQuestMarkerRenderer} -- see that class's own doc
     * for why this can't just reuse the shared "?" nameplate mechanism.
     */
    public static boolean anchorHasUrgentPersonalBusiness(ServerPlayer player, ServerLevel level, com.github.cerealklla.settlemynts.plotsign.ShopAnchor anchor) {
        if (!com.github.cerealklla.settlemynts.bridge.MyssionsQuestBridge.isLoaded()) {
            return false;
        }
        return resolveShopContext(level, anchor)
                .map(ctx -> {
                    PlotRecord plot = ctx.plot();
                    return com.github.cerealklla.settlemynts.bridge.MyssionsQuestBridge.hasUrgentPersonalBusiness(
                            player, plot.plotId(), plot.zoneTypeId().toString());
                })
                .orElse(false);
    }

    // The real, canonical Blueprynts-bridged Private Residence zone type, plus the legacy
    // settlemynts:private_residence id a pre-2026-10-01 plot might still carry (see
    // zone.ZoneTypeRegistry's own ALIASES map for the full story) -- "Change Job" needs to
    // recognize both, the same way that registry's own lookups already do.
    private static final Identifier PRIVATE_RESIDENCE_ZONE_TYPE_ID = Identifier.fromNamespaceAndPath("blueprynts", "private_residence");
    private static final Identifier PRIVATE_RESIDENCE_LEGACY_ZONE_TYPE_ID = Identifier.fromNamespaceAndPath(MODID, "private_residence");

    /**
     * Every button {@code client.NpcMenuScreen} can show, computed once and shared by both {@link
     * #requestNpcMenu} (what actually opens the menu) and {@code plotsign.PlotShopProximityTicker}
     * (deciding whether the "Press G" prompt itself should even appear) -- the two can never
     * disagree about whether there's "something here" this way.
     *
     * <p>{@code canManageStore} is deliberately NARROWER than {@code zone.PlotPermissions#canManage}
     * (explicit user spec, 2026-10-16: "Visible to Owner if non-NPC, otherwise visible to Mayor or
     * Town Planners") -- on a player-owned plot this drops {@code canManage}'s usual founder-always-
     * wins override, showing "Manage Store" only to that plot's own real owner. This is scoped to
     * the NPC Menu alone; the Plot Config Sign's own separate "Manage Shop" button still uses the
     * wider, unchanged {@code canManage} (right-clicking the physical sign is a different
     * interaction surface this request didn't ask to touch).
     *
     * <p>{@code canChangeJob} needs {@code zoneTypeId} to actually be Private Residence -- explicit
     * user follow-up, "I forgot to specify, the 'change job' is only available for an NPC on a
     * private property plot." A Farm/Lumberyard plot already gets the matching worker for free via
     * {@code zone.PlotWorkerKind}; "Change Job" exists specifically to let a Private Residence
     * owner's hired NPC do productive work instead of just standing around decoratively.
     */
    public static NpcMenuOptions computeNpcMenuOptions(ServerPlayer player, ServerLevel level, com.github.cerealklla.settlemynts.plotsign.ShopAnchor.Npc anchor) {
        java.util.Optional<ShopContext> resolved = resolveShopContext(level, anchor);
        if (resolved.isEmpty()) {
            return NpcMenuOptions.NONE;
        }
        PlotRecord plot = resolved.get().plot();
        com.github.cerealklla.settlemynts.zone.PlotOwner owner = resolved.get().owner();
        UUID playerId = player.getUUID();

        boolean canOpenStore = npcHasOpenableShop(level, anchor);

        boolean isOwnerViewing = plot.owner().map(playerId::equals).orElse(false);
        boolean canManageStore;
        if (!canOpenStore) {
            canManageStore = false;
        } else if (plot.owner().isPresent()) {
            canManageStore = isOwnerViewing;
        } else {
            canManageStore = owner instanceof GhostTownHallCoreEntity core && core.isTownPlanner(playerId);
        }

        boolean isPrivateResidence = plot.zoneTypeId().equals(PRIVATE_RESIDENCE_ZONE_TYPE_ID)
                || plot.zoneTypeId().equals(PRIVATE_RESIDENCE_LEGACY_ZONE_TYPE_ID);
        boolean canChangeJob = isOwnerViewing && isPrivateResidence;

        boolean canFireNpc = isOwnerViewing && plot.npcHired();

        // Real report, 2026-10-19: "This NPC has a ? on his head but no Talk menu." The public "?"
        // nameplate (PlotShopProximityTicker#updateWishlistNameplate, driven by anchorHasWishlist)
        // fires for an unmet Planned Inventory deficit or a pending quest board offer on ANY plot,
        // ownership included -- it was never actually restricted to NPC-owned plots. "Talk" (the only
        // way to view/act on either) was hardcoded NPC-owned-only, so a player-owned plot's marker was
        // a dead end with no way to ever reach it. Widened to match the real trigger instead of a
        // stale ownership assumption from the original unified-menu design.
        //
        // Also folds in anchorHasPersonalQuestBusiness, which the 2026-10-16 NPC Menu overhaul
        // appears to have silently dropped entirely -- that method's own doc (above) describes
        // exactly the 2026-10-11 bug this was built to fix ("accepted a quest, board slot cleared,
        // now there's no way to come back and turn it in"), but nothing in the new unified menu ever
        // called it; canTalk here checked only plot ownership. Without this, that old regression was
        // silently reintroduced by the menu rework.
        boolean canTalk = anchorHasWishlist(level, anchor) || anchorHasPersonalQuestBusiness(player, level, anchor);

        return new NpcMenuOptions(canOpenStore, canManageStore, canChangeJob, canFireNpc, canTalk);
    }

    public record NpcMenuOptions(boolean canOpenStore, boolean canManageStore, boolean canChangeJob, boolean canFireNpc, boolean canTalk) {
        public static final NpcMenuOptions NONE = new NpcMenuOptions(false, false, false, false, false);

        public boolean any() {
            return canOpenStore || canManageStore || canChangeJob || canFireNpc || canTalk;
        }
    }

    /** Whether {@code client.NpcMenuScreen} would show anything at all for this NPC right now -- see {@link #computeNpcMenuOptions}'s own doc. */
    public static boolean npcMenuHasAnyOption(ServerPlayer player, ServerLevel level, com.github.cerealklla.settlemynts.plotsign.ShopAnchor.Npc anchor) {
        return computeNpcMenuOptions(player, level, anchor).any();
    }

    private static void requestNpcMenu(com.github.cerealklla.settlemynts.plotsign.RequestNpcMenuPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        openNpcMenu(player, serverLevel, payload.anchor());
    }

    /** Sends {@code client.NpcMenuScreen}'s data -- shared by {@link #requestNpcMenu} (the "G" key)
     *  and {@code resident.VillagerShopInteractListener} (a plain right-click), so both paths into
     *  the menu stay identical. A no-op if {@code anchor} no longer resolves to a real entity. */
    public static void openNpcMenu(ServerPlayer player, ServerLevel level, com.github.cerealklla.settlemynts.plotsign.ShopAnchor.Npc anchor) {
        if (!(level.getEntity(anchor.entityId()) instanceof net.minecraft.world.entity.LivingEntity npc)) {
            return;
        }
        NpcMenuOptions options = computeNpcMenuOptions(player, level, anchor);
        String npcName = npc.getName().getString();
        PacketDistributor.sendToPlayer(player, new com.github.cerealklla.settlemynts.plotsign.OpenNpcMenuPayload(
                anchor, npcName, options.canOpenStore(), options.canManageStore(), options.canChangeJob(), options.canFireNpc(), options.canTalk()));
    }

    /**
     * Resolves the {@code plotsign.PlotConfigSignBlockEntity} for whichever plot {@code anchor}
     * (an NPC, not a sign position) belongs to -- "Change Job"/"Fire NPC" both need the real sign to
     * discard a worker through ({@code zone.NpcHireMaintenance#despawnHiredWorker}), but only ever
     * have an NPC anchor to start from. Same {@code PlotConfigSignIndex} plotId lookup {@code
     * zone.PlotNpcDeathListener} already uses for the identical problem.
     */
    private static java.util.Optional<com.github.cerealklla.settlemynts.plotsign.PlotConfigSignBlockEntity> resolveSignForPlot(net.minecraft.server.MinecraftServer server, UUID plotId) {
        return com.github.cerealklla.settlemynts.plotsign.PlotConfigSignIndex.get(server).get(plotId).flatMap(globalPos -> {
            ServerLevel signLevel = server.getLevel(globalPos.dimension());
            if (signLevel == null || !(signLevel.getBlockEntity(globalPos.pos()) instanceof com.github.cerealklla.settlemynts.plotsign.PlotConfigSignBlockEntity sign)) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(sign);
        });
    }

    private static void setPlotJob(com.github.cerealklla.settlemynts.plotsign.SetPlotJobPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        com.github.cerealklla.settlemynts.plotsign.ShopAnchor.Npc anchor = payload.anchor();
        java.util.Optional<ShopContext> resolved = resolveShopContext(serverLevel, anchor);
        if (resolved.isEmpty() || !(resolved.get().owner() instanceof GhostTownHallCoreEntity core)) {
            player.sendSystemMessage(Component.literal("Couldn't resolve this plot anymore."));
            return;
        }
        PlotRecord plot = resolved.get().plot();
        NpcMenuOptions options = computeNpcMenuOptions(player, serverLevel, anchor);
        if (!options.canChangeJob()) {
            player.sendSystemMessage(Component.literal("You can't change this NPC's job."));
            return;
        }
        core.updatePlot(plot.withJob(payload.job()));
        resolveSignForPlot(serverLevel.getServer(), plot.plotId())
                .ifPresent(sign -> com.github.cerealklla.settlemynts.zone.NpcHireMaintenance.despawnHiredWorker(serverLevel, sign));
        player.sendSystemMessage(Component.literal(payload.job() == com.github.cerealklla.settlemynts.zone.PlotJob.NONE
                ? "Job cleared -- the NPC will go back to its usual role."
                : "Job changed to " + payload.job().name().charAt(0) + payload.job().name().substring(1).toLowerCase(java.util.Locale.ROOT) + "."));
    }

    private static void requestFireNpc(com.github.cerealklla.settlemynts.plotsign.RequestFireNpcPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        com.github.cerealklla.settlemynts.plotsign.ShopAnchor.Npc anchor = payload.anchor();
        java.util.Optional<ShopContext> resolved = resolveShopContext(serverLevel, anchor);
        if (resolved.isEmpty() || !(resolved.get().owner() instanceof GhostTownHallCoreEntity core)) {
            player.sendSystemMessage(Component.literal("Couldn't resolve this plot anymore."));
            return;
        }
        PlotRecord plot = resolved.get().plot();
        NpcMenuOptions options = computeNpcMenuOptions(player, serverLevel, anchor);
        if (!options.canFireNpc()) {
            player.sendSystemMessage(Component.literal("You can't fire this NPC."));
            return;
        }
        core.updatePlot(plot.withNpcHired(false));
        resolveSignForPlot(serverLevel.getServer(), plot.plotId())
                .ifPresent(sign -> com.github.cerealklla.settlemynts.zone.NpcHireMaintenance.despawnHiredWorker(serverLevel, sign));
        player.sendSystemMessage(Component.literal("Fired. Click \"Hire NPC\" again whenever you want to re-staff this plot."));
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

    /** "Defense Spending..." button click on {@code client.FoundingScreen} -- the Ghost Town Hall Core's own direct menu, same resolve/validate shape as {@link #setBoundaryVisible}. */
    private static void requestDefenseSpending(com.github.cerealklla.settlemynts.founding.RequestDefenseSpendingPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!(serverLevel.getEntity(payload.coreEntityId()) instanceof GhostTownHallCoreEntity core)) {
            player.sendSystemMessage(Component.literal(
                    "Couldn't find this settlement's Town Hall Core anymore -- try right-clicking it again to reopen this screen."));
            return;
        }
        if (!core.isTownPlanner(player.getUUID())) {
            player.sendSystemMessage(Component.literal("You're not a Town Planner of this settlement."));
            return;
        }
        List<com.github.cerealklla.settlemynts.founding.DefenseSpendingEntry> entries = new ArrayList<>();
        for (PlotRecord p : core.getPlots()) {
            if (!com.github.cerealklla.settlemynts.guardhouse.DefenseSpendingTransfer.isEligible(p)) {
                continue;
            }
            int allocation = com.github.cerealklla.settlemynts.guardhouse.DefenseSpendingTransfer.resolveRecipient(serverLevel, p)
                    .map(com.github.cerealklla.settlemynts.guardhouse.DefenseSpendingRecipient::defenseSpendingAllocation)
                    .orElse(0);
            entries.add(new com.github.cerealklla.settlemynts.founding.DefenseSpendingEntry(p.plotId(), p.name(), allocation));
        }
        PacketDistributor.sendToPlayer(player, new com.github.cerealklla.settlemynts.founding.OpenDefenseSpendingMenuPayload(payload.coreEntityId(), entries));
    }

    /** "Set" button click on {@code client.DefenseSpendingScreen} -- re-validates {@code isTownPlanner} against the Town Hall Core, then matches {@code guardhousePlotId} against this SAME settlement's own plot list before applying. */
    private static void setDefenseSpending(com.github.cerealklla.settlemynts.founding.SetDefenseSpendingPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!(serverLevel.getEntity(payload.coreEntityId()) instanceof GhostTownHallCoreEntity core)) {
            player.sendSystemMessage(Component.literal(
                    "Couldn't find this settlement's Town Hall Core anymore -- try right-clicking it again to reopen this screen."));
            return;
        }
        if (!core.isTownPlanner(player.getUUID())) {
            player.sendSystemMessage(Component.literal("You're not a Town Planner of this settlement."));
            return;
        }
        PlotRecord recipientPlot = core.getPlots().stream().filter(p -> p.plotId().equals(payload.guardhousePlotId())
                && com.github.cerealklla.settlemynts.guardhouse.DefenseSpendingTransfer.isEligible(p))
                .findFirst().orElse(null);
        if (recipientPlot == null) {
            player.sendSystemMessage(Component.literal("Couldn't resolve that plot anymore."));
            return;
        }
        com.github.cerealklla.settlemynts.guardhouse.DefenseSpendingTransfer.resolveRecipient(serverLevel, recipientPlot)
                .ifPresent(recipient -> recipient.setDefenseSpendingAllocation(payload.allocation()));
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
        // Event-triggered road re-tiering (2026-10-10, replacing the old RoadwayTierTicker poll) --
        // only the Town Hall plot's own Tier drives road appearance (see RoadwayTierResolver), and
        // this is the only place that Tier can ever change, so there's nothing left to poll for.
        if (isTownHall) {
            com.github.cerealklla.settlemynts.roadway.RoadwayRetier.repaintSettlementRoads(serverLevel, core.getUUID(), newTier);
        }
        // Highway re-tiering (2026-10-13, explicit user spec) -- unlike Roadway above, a Highway's
        // Tier is driven by the settlement's own SETTLEMENT TIER (the average of EVERY plot, see
        // SettlementTierResolver), not just the Town Hall specifically, so this fires on ANY plot
        // upgrade, not only isTownHall. core.getPlots() already reflects the just-applied upgrade
        // (updatePlot above), so this is the real, current average, not a stale pre-upgrade value.
        com.github.cerealklla.settlemynts.roadway.HighwayRetier.retierHighwaysForSettlement(
                serverLevel, core.getUUID(), com.github.cerealklla.settlemynts.roadway.SettlementTierResolver.averageTier(core.getPlots()));
        grantMayorSkillXpForPlotUpgrade(serverLevel.getServer(), core, newTier);
        // Real live report, 2026-10-12: a Myssions "help upgrade this plot" quest themed around this
        // exact upgrade had no way to find out it happened, and stayed stuck forever even though the
        // real materials were spent and the Tier really did go up. See bridge.MyssionsQuestBridge
        // #notifyPlotUpgraded's own doc.
        if (com.github.cerealklla.settlemynts.bridge.MyssionsQuestBridge.isLoaded()) {
            com.github.cerealklla.settlemynts.bridge.MyssionsQuestBridge.notifyPlotUpgraded(player, plot.plotId());
        }
        player.sendSystemMessage(Component.literal("Plot upgraded to Tier " + newTier + "! Use the Construction Box to build at the new Tier."));
    }

    /**
     * "Hire NPC" (design doc Section 14a, explicit user request, 2026-10-15) -- pays {@code
     * zone.NpcHireMaintenance#costFor} right now, out of the plot's own boxes, and (on success) sets
     * {@code PlotRecord#npcHired} so the existing resident/worker spawn tickers pick it up within
     * their own next ~5-second scan -- no separate spawn call needed here. Also the real recovery
     * path from a funds-driven despawn ({@code zone.NpcMaintenanceTicker}): {@code npcHired} stays
     * {@code true} through a despawn, so this just needs to pay again to bring the worker back.
     */
    private static void requestHireNpc(com.github.cerealklla.settlemynts.plotsign.RequestHireNpcPayload payload, IPayloadContext context) {
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
        if (plot.owner().isEmpty()) {
            player.sendSystemMessage(Component.literal("This plot already has a free NPC -- no need to hire one."));
            return;
        }
        if (plot.zoneTypeId().equals(com.github.cerealklla.settlemynts.guardhouse.GuardhouseConstants.GUARDHOUSE_ZONE_TYPE_ID)
                || plot.zoneTypeId().equals(GhostTownHallCoreEntity.TOWN_HALL_ZONE_TYPE_ID)) {
            player.sendSystemMessage(Component.literal("This plot type can't hire an NPC."));
            return;
        }
        long currentDay = com.github.cerealklla.settlemynts.zone.NpcHireMaintenance.currentDay(serverLevel.getServer());
        if (plot.npcHired() && plot.npcLastChargedDay() == currentDay) {
            player.sendSystemMessage(Component.literal("This plot's hired NPC is already paid up for today."));
            return;
        }
        int cost = com.github.cerealklla.settlemynts.zone.NpcHireMaintenance.costFor(plot);
        if (!com.github.cerealklla.settlemynts.zone.NpcHireMaintenance.tryCharge(serverLevel, plot.plotId(), cost)) {
            player.sendSystemMessage(Component.literal("Not enough Gold Nuggets in this plot's storage -- need " + cost + "."));
            return;
        }
        core.updatePlot(plot.withNpcHired(true).withNpcLastChargedDay(currentDay));
        player.sendSystemMessage(Component.literal("Hired! Paid " + cost + " Gold Nuggets -- an NPC will arrive shortly, and costs "
                + cost + " more every midnight to keep."));
    }

    // 2 real miles (1 block ~= 1 meter, 1 mile ~= 1609 blocks, same convention as every other
    // distance constant in this suite) -- matches the user's own explicit spec for the Establish
    // Highway map picker's candidate search radius.
    private static final double ESTABLISH_HIGHWAY_SEARCH_RADIUS_BLOCKS = 2.0 * 1609.0;
    private static final int ESTABLISH_HIGHWAY_COST_GOLD = 1000;

    /**
     * "Establish Highway" button click -- added 2026-10-13. Resolves this Town Hall's own real
     * Cartographyr placeId and every settlement the Mayor has personal knowledge of within {@link
     * #ESTABLISH_HIGHWAY_SEARCH_RADIUS_BLOCKS} (via {@code bridge.LyfeKnownSettlementsBridge}),
     * excluding any pair that already has a road-quest entry of any kind, then sends the candidate
     * list to open {@code client.EstablishHighwayMapScreen}. No cost/permission check happens here
     * beyond "can this player even see the button's own gates" -- real re-validation (founder-only,
     * gold, pair-still-free) happens at {@link #requestCreateHighwayQuest}.
     */
    private static void requestEstablishHighwayMap(com.github.cerealklla.settlemynts.plotsign.RequestEstablishHighwayMapPayload payload, IPayloadContext context) {
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
        if (plot == null || !plot.zoneTypeId().equals(GhostTownHallCoreEntity.TOWN_HALL_ZONE_TYPE_ID)
                || !PlotPermissions.canManage(plot, core, player.getUUID())) {
            player.sendSystemMessage(Component.literal("You can't manage this plot."));
            return;
        }
        if (!core.isFounder(player.getUUID())) {
            player.sendSystemMessage(Component.literal("Only this settlement's Mayor can establish a Highway."));
            return;
        }
        Long originPlaceId = core.getCartographyrEntityId();
        if (originPlaceId == null) {
            player.sendSystemMessage(Component.literal("This settlement isn't registered with the world map yet."));
            return;
        }
        if (!com.github.cerealklla.settlemynts.bridge.LyfeKnownSettlementsBridge.isLoaded()
                || !com.github.cerealklla.settlemynts.bridge.MyssionsQuestBridge.isLoaded()) {
            player.sendSystemMessage(Component.literal("Establish Highway needs both Lyfe and Myssions loaded."));
            return;
        }
        net.minecraft.core.BlockPos origin = core.blockPosition();
        var known = com.github.cerealklla.settlemynts.bridge.LyfeKnownSettlementsBridge.getKnownSettlementsNear(
                player, serverLevel, origin, ESTABLISH_HIGHWAY_SEARCH_RADIUS_BLOCKS);
        List<com.github.cerealklla.settlemynts.plotsign.EstablishHighwayCandidatesPayload.Candidate> candidates = new ArrayList<>();
        for (var result : known) {
            if (result.placeId() == originPlaceId
                    || com.github.cerealklla.settlemynts.bridge.MyssionsQuestBridge.hasAnyRoadQuestEntry(
                            serverLevel.getServer(), originPlaceId, result.placeId())) {
                continue; // Can't target itself, or a pair that already has a road-quest entry of any kind.
            }
            double dx = result.x() - origin.getX();
            double dz = result.z() - origin.getZ();
            var thresholds = com.github.cerealklla.settlemynts.bridge.MyssionsQuestBridge.computeRoadQuestStakeThresholds(Math.sqrt(dx * dx + dz * dz));
            candidates.add(new com.github.cerealklla.settlemynts.plotsign.EstablishHighwayCandidatesPayload.Candidate(
                    result.placeId(), result.x(), result.z(), result.displayText(), result.fullyKnown(),
                    thresholds.minStakes(), thresholds.optimalStakeCap()));
        }
        PacketDistributor.sendToPlayer(player, new com.github.cerealklla.settlemynts.plotsign.EstablishHighwayCandidatesPayload(
                payload.signPos(), originPlaceId, origin.getX(), origin.getZ(), candidates));
    }

    /**
     * "Create Quest" button click on {@code client.EstablishHighwayMapScreen} -- added 2026-10-13.
     * Re-resolves and re-validates everything from scratch (never trusts the client): founder-only,
     * the settlement pair still free, and 1000 Gold Nuggets physically present in the Town Hall
     * plot's own boxes. Posts the quest (via Myssions' already-built {@code
     * quest.RoadQuestLogic#postOffer}) BEFORE draining any gold -- if posting fails for any reason
     * (e.g. the pair was just taken by a concurrent post), nothing is charged.
     */
    private static void requestCreateHighwayQuest(com.github.cerealklla.settlemynts.plotsign.RequestCreateHighwayQuestPayload payload, IPayloadContext context) {
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
        if (plot == null || !plot.zoneTypeId().equals(GhostTownHallCoreEntity.TOWN_HALL_ZONE_TYPE_ID)
                || !PlotPermissions.canManage(plot, core, player.getUUID())) {
            player.sendSystemMessage(Component.literal("You can't manage this plot."));
            return;
        }
        if (!core.isFounder(player.getUUID())) {
            player.sendSystemMessage(Component.literal("Only this settlement's Mayor can establish a Highway."));
            return;
        }
        Long originPlaceId = core.getCartographyrEntityId();
        if (originPlaceId == null || !com.github.cerealklla.settlemynts.bridge.MyssionsQuestBridge.isLoaded()) {
            player.sendSystemMessage(Component.literal("Couldn't resolve this settlement anymore."));
            return;
        }
        if (plot.constructionBoxId().isEmpty()) {
            player.sendSystemMessage(Component.literal("This plot has no Construction Box to draw gold from."));
            return;
        }
        List<net.minecraft.world.Container> boxes = com.github.cerealklla.settlemynts.api.Settlemynts.resolvePlotBoxes(serverLevel, plot.plotId());
        int goldOnHand = countGoldNuggets(boxes);
        if (goldOnHand < ESTABLISH_HIGHWAY_COST_GOLD) {
            player.sendSystemMessage(Component.literal(
                    "Not enough gold in the Town Hall's boxes -- need " + (ESTABLISH_HIGHWAY_COST_GOLD - goldOnHand) + " more Gold Nuggets."));
            return;
        }
        boolean posted = com.github.cerealklla.settlemynts.bridge.MyssionsQuestBridge.postRoadQuestOffer(
                serverLevel, originPlaceId, payload.destinationPlaceId(), plot.plotId(), player.getUUID());
        if (!posted) {
            player.sendSystemMessage(Component.literal("Couldn't post the Highway quest -- that pair may already have one."));
            return;
        }
        drainGoldNuggets(boxes, ESTABLISH_HIGHWAY_COST_GOLD);
        if (net.neoforged.fml.ModList.get().isLoaded("blueprynts")) {
            // Resolved live via constructionBoxId, not the stale plot.boxPos() snapshot (2026-10-16 fix).
            com.github.cerealklla.settlemynts.bridge.BlueprintsConstructionBridge.appendLedgerEntry(
                    serverLevel, plot, "Highway Quest Creation -" + ESTABLISH_HIGHWAY_COST_GOLD);
        }
        player.sendSystemMessage(Component.literal("Highway quest posted! It'll appear at the Town Hall under Mayor's Special Assignments."));
    }

    private static int countGoldNuggets(List<net.minecraft.world.Container> boxes) {
        int total = 0;
        for (net.minecraft.world.Container box : boxes) {
            for (int slot = 0; slot < box.getContainerSize(); slot++) {
                ItemStack stack = box.getItem(slot);
                if (stack.is(net.minecraft.world.item.Items.GOLD_NUGGET)) {
                    total += stack.getCount();
                }
            }
        }
        return total;
    }

    private static void drainGoldNuggets(List<net.minecraft.world.Container> boxes, int amount) {
        int remaining = amount;
        for (net.minecraft.world.Container box : boxes) {
            for (int slot = 0; slot < box.getContainerSize() && remaining > 0; slot++) {
                ItemStack stack = box.getItem(slot);
                if (!stack.is(net.minecraft.world.item.Items.GOLD_NUGGET)) {
                    continue;
                }
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                remaining -= take;
            }
        }
    }

    /**
     * "Gains XP any time a plot in a Settlement you are the mayor of is upgraded. Gain half xp if you
     * are a Town Planner for that town but not the Mayor" (Lyfe's Mayor skill, added 2026-10-09) --
     * passive, so this always grants to every qualifying player regardless of who actually clicked
     * "Upgrade Plot," not just the acting player.
     */
    public static void grantMayorSkillXpForPlotUpgrade(net.minecraft.server.MinecraftServer server, GhostTownHallCoreEntity core, int newTier) {
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
            BlueprintsConstructionBridge.Placement placement = BlueprintsConstructionBridge.placeConstructionBox(serverLevel, site, zoneTypeId, core.getUUID(), plotSessionId);
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
        // plots will not have a recurring rent," explicit user decision; Town Hall is npcOwnedOnly
        // so it never reaches this branch at all). Box lists are kept in sync incrementally from
        // here on by bills.PlotBillBoxListener (reworked 2026-10-22, see decisions.md) as boxes are
        // placed/broken -- sourceBoxes starts empty since a just-finalized plot can't already have a
        // box in it, but destinationBoxes is seeded once here from the settlement's Town Hall plot's
        // *current* boxes, since no placement event will ever fire for a box that already existed
        // before this bill did.
        Optional<UUID> billId = Optional.empty();
        if (owner.isPresent() && YconomicsBillBridge.isAvailable()) {
            List<YconomicsBillBridge.BoxRef> destinationBoxes = core.townHallPlot()
                    .map(townHall -> com.github.cerealklla.settlemynts.bills.PlotBoxDiscovery.findBoxes(serverLevel, townHall.cartographyrPlotEntityId()))
                    .orElse(List.of());
            billId = Optional.of(YconomicsBillBridge.registerPlotBill(serverLevel, plotSessionId, List.of(), destinationBoxes,
                    Map.of(Identifier.withDefaultNamespace("gold_nugget"), 1), 1L));
        }

        PlotRecord plotRecord = new PlotRecord(plotSessionId, payload.name(), zoneTypeId, plotEntity.id().value(), bufferEntity.id().value(), boxPos, constructionBoxId, owner, billId, Optional.empty(), java.util.List.of(), 1, java.util.List.of(), 0, PlotRecord.HiredNpcState.NONE, 0);
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

        // Watch Tower's own Beacon is NOT spawned here (real correction, 2026-10-22 -- "I wanted the
        // custom block to be placed within the blueprynt itself, like a light at the top of a
        // Lighthouse") -- it's just an ordinary block the Ancestors Blueprint places, and
        // self-resolves its own plot/settlement identity once pasted -- see
        // WatchTowerBeaconBlockEntity#tryResolveIdentity's own doc.
        if (zoneTypeId.equals(com.github.cerealklla.settlemynts.watchtower.WatchTowerConstants.WATCH_TOWER_ZONE_TYPE_ID)) {
            com.github.cerealklla.settlemynts.zone.ShopSeeding.seedWatchTowerDefaults(core, plotRecord);
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
        } else if (player.getMainHandItem().is(ModItems.HIGHWAY_STAKE.get())) {
            // Real live report, 2026-10-13: Highway Stakes never got this readout at all when this
            // method was built for Roadway Stakes -- Highway Stakes didn't exist yet that day.
            com.github.cerealklla.settlemynts.roadway.HighwayStakeEntity anchor =
                    com.github.cerealklla.settlemynts.roadway.HighwayStakeLeash.resolveGhostAnchor(serverLevel, player.getUUID());
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

            // Highway Stakes (added 2026-10-11) never got this same block -- real live report,
            // 2026-10-13: "exactly the same behavior as roadway stakes in a town; you'd have to go
            // back and reattach the line for the next stake to be connected," which Roadway Stakes
            // already get from the matching block above (switching away clears the ghost anchor, so
            // the next placement doesn't silently resume a stale chain) but Highway Stakes never did.
            if (player.getMainHandItem().is(ModItems.HIGHWAY_STAKE.get())) {
                com.github.cerealklla.settlemynts.roadway.HighwayStakeEntity anchor =
                        com.github.cerealklla.settlemynts.roadway.HighwayStakeLeash.resolveGhostAnchor(serverLevel, player.getUUID());
                if (anchor != null) {
                    applyLeashPull(player, new Vec3(anchor.getX(), anchor.getY(), anchor.getZ()),
                            com.github.cerealklla.settlemynts.roadway.HighwayStakeEntity.MAX_CONNECTION_LENGTH_BLOCKS);
                }
            } else {
                com.github.cerealklla.settlemynts.roadway.HighwayStakeLeash.clearGhostAnchor(player.getUUID());
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
                removeStaleGuardKitStandsOutsidePlot(player, serverLevel);
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

    /**
     * Sweeps every Guard Kit Stand item in {@code player}'s inventory (any slot, same "carrying it
     * while outside, not holding it" rule as {@link #removeStalePlotItemsOutsideSettlement}) and
     * deletes any whose owning *plot* (not the whole settlement -- a Guard Kit Stand belongs to one
     * specific Guardhouse plot) no longer contains the player's position. Also called directly from
     * {@link com.github.cerealklla.settlemynts.guardhouse.GuardKitStandRemovalListener} on death/
     * logout for instant handling, per the explicit spec ("dying/teleporting away/logging out") --
     * the periodic tick check alone already covers "teleporting away," since that just changes the
     * player's position by the next scan.
     */
    public static void removeStaleGuardKitStandsOutsidePlot(ServerPlayer player, ServerLevel serverLevel) {
        var inventory = player.getInventory();
        BlockPos pos = player.blockPosition();
        boolean removedAny = false;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.is(com.github.cerealklla.settlemynts.registration.ModItems.GUARD_KIT_STAND.get())) {
                continue;
            }
            com.github.cerealklla.settlemynts.guardhouse.GuardKitStandData data =
                    stack.get(com.github.cerealklla.settlemynts.registration.ModItems.GUARD_KIT_STAND_DATA.get());
            if (data == null || !(serverLevel.getEntity(data.settlementCoreId()) instanceof GhostTownHallCoreEntity core)) {
                continue;
            }
            Optional<PlotRecord> containing = com.github.cerealklla.settlemynts.zone.PlotGeometry.findContainingPlot(
                    serverLevel, core, pos.getX(), pos.getZ());
            if (containing.isPresent() && containing.get().plotId().equals(data.plotId())) {
                continue; // Still inside its own plot -- nothing to do.
            }
            inventory.setItem(slot, ItemStack.EMPTY);
            removedAny = true;
            PlotRecord plot = core.getPlots().stream().filter(p -> p.plotId().equals(data.plotId())).findFirst().orElse(null);
            if (plot != null) {
                core.updatePlot(plot.withGuardKitStandsIssued(Math.max(0, plot.guardKitStandsIssued() - 1)));
            }
        }
        if (removedAny) {
            player.sendSystemMessage(Component.literal("You left the plot -- your Guard Kit Stand has been removed."));
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
