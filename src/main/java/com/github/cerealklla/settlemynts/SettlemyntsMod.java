package com.github.cerealklla.settlemynts;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
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
import com.github.cerealklla.settlemynts.api.Settlemynts;
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
import com.github.cerealklla.settlemynts.registration.ModEntities;
import com.github.cerealklla.settlemynts.registration.ModItems;
import com.github.cerealklla.settlemynts.zone.FinalizePlotPayload;
import com.github.cerealklla.settlemynts.zone.GhostPlotFencePostEntity;
import com.github.cerealklla.settlemynts.zone.GhostPlotStakeEntity;
import com.github.cerealklla.settlemynts.zone.GhostPlotWallEntity;
import com.github.cerealklla.settlemynts.zone.OpenPlotStakeScreenPayload;
import com.github.cerealklla.settlemynts.zone.PlotGeometry;
import com.github.cerealklla.settlemynts.zone.PlotRecord;
import com.github.cerealklla.settlemynts.zone.PlotSessionData;
import com.github.cerealklla.settlemynts.zone.RemovePlotStakePayload;
import com.github.cerealklla.settlemynts.zone.RequestPlotStakePayload;
import com.github.cerealklla.settlemynts.zone.SetShowPlotPerimetersPayload;
import com.github.cerealklla.settlemynts.zone.ZoneType;
import com.github.cerealklla.settlemynts.zone.ZoneTypeRegistry;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
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

        Cartography.registerLayer(new Layer(ZONE_LAYER_ID, "Zone", -1));

        // Built-in zone types (design doc Section 11a) -- Settlemynts only ships these two; a
        // future Blueprynts mod (and others) is expected to register the rest via
        // Settlemynts.registerZoneType. Wool, not glass (switched 2026-09-26, playtest feedback --
        // glass read as too see-through/insubstantial for a wall). Colors chosen to be unique/
        // distinct while the 16 wool colors last -- see ZoneType's own doc for the fallback plan
        // once they run out.
        Settlemynts.registerZoneType(new ZoneType(
                Identifier.fromNamespaceAndPath(MODID, "town_hall"), "Town Hall", Blocks.WHITE_WOOL));
        Settlemynts.registerZoneType(new ZoneType(
                Identifier.fromNamespaceAndPath(MODID, "private_residence"), "Private Residence", Blocks.LIGHT_BLUE_WOOL));

        NeoForge.EVENT_BUS.register(this);
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::registerPayloads);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Settlemynts common setup");
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
                            .ifPresent(lastStake -> lastStake.remove(player));
                    GhostPlotFencePostEntity.regenerate(serverLevel, ownerCoreId, plotSessionId);
                });

        registrar.playToServer(FinalizePlotPayload.TYPE, FinalizePlotPayload.STREAM_CODEC,
                (payload, context) -> finalizePlot(payload, context));

        registrar.playToServer(SetShowPlotPerimetersPayload.TYPE, SetShowPlotPerimetersPayload.STREAM_CODEC,
                (payload, context) -> setShowPlotPerimeters(payload, context));
    }

    /** "Get Plot Placement Stake" -- resolves (or starts) this planner's active plot session for the settlement, then grants a stake item bound to it. */
    private static void requestPlotStake(RequestPlotStakePayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)
                || !(player.level() instanceof ServerLevel serverLevel)
                || !(serverLevel.getEntity(payload.coreEntityId()) instanceof GhostTownHallCoreEntity core)
                || !core.isTownPlanner(player.getUUID())) {
            return;
        }
        UUID plotSessionId = core.getActivePlotSession(player.getUUID());
        if (plotSessionId == null) {
            plotSessionId = UUID.randomUUID();
            core.setActivePlotSession(player.getUUID(), plotSessionId);
        }
        ItemStack stake = new ItemStack(ModItems.PLOT_PLACEMENT_STAKE.get());
        stake.set(ModItems.PLOT_SESSION_DATA, new PlotSessionData(core.getUUID(), plotSessionId));
        if (!player.getInventory().add(stake)) {
            player.drop(stake, false);
        }
    }

    /**
     * "Finalize Plot" -- computes the plot's real polygon and its "Town Proper" buffer from the
     * session's placed stakes (design doc Section 11a), registers both with Cartographyr, records
     * the result on the core, clears the session's stakes/leftover items, and frees this planner's
     * active-session slot so their next "Get Plot Placement Stake" starts a fresh plot.
     */
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

        core.addPlot(new PlotRecord(plotSessionId, payload.name(), zoneTypeId, plotEntity.id().value(), bufferEntity.id().value()));
        core.setActivePlotSession(player.getUUID(), null);

        for (GhostPlotStakeEntity stake : stakes) {
            stake.discard();
        }
        GhostPlotFencePostEntity.regenerate(serverLevel, ownerCoreId, plotSessionId); // No stakes left -- clears the preview.
        int cleared = clearPlotStakeItems(serverLevel, core, plotSessionId);

        player.sendSystemMessage(Component.literal(
                "Plot \"" + payload.name() + "\" (" + zoneType.get().label() + ") finalized and registered with Cartographyr"
                        + (cleared > 0 ? " -- cleared " + cleared + " leftover Plot Placement Stake item(s)." : ".")));
    }

    /** Mirrors {@link #clearPerimeterStakeItems} -- removes only stake items bound to this specific finished plot session, not every Plot Placement Stake the planner happens to be carrying (they may be mid-staking a different plot). */
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
                    inventory.setItem(slot, ItemStack.EMPTY);
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
                int groundY = serverLevel.getHeight(Heightmap.Types.WORLD_SURFACE, block.x(), block.z());
                for (int level = 0; level < GhostPlotWallEntity.WALL_HEIGHT_BLOCKS; level++) {
                    GhostPlotWallEntity.create(serverLevel, block.x() + 0.5, groundY + level, block.z() + 0.5, core.getUUID(), zoneType.wallBlock().defaultBlockState());
                }
            }
        }
    }

    /**
     * Toggles "View Settlement Boundaries" (design doc Section 9). Turning it on discards any
     * existing wall (in case one was left over from a stale state) and generates a fresh one from
     * the settlement's *current* stake positions -- always live, not a snapshot from whenever it
     * was last toggled on. Turning it off just discards every wall point for this core.
     *
     * <p>The wall itself marks the first NOT-permitted ring, same meaning as the plot wall (2026-09-27,
     * explicit user request to make this consistent) -- traced via {@link Geometry.Polygon#outerRing}
     * one block outside the settlement's real, block-inclusive core boundary ({@link
     * Geometry.Polygon#coveringBlocks}), not the raw stake positions directly. Computed live from the
     * stakes here (not read back from Cartographyr) since this toggle works both before and after
     * Finalize, and the core entity only exists once finalized. Stakes are read in **placement
     * order** (2026-09-27, see decisions.md same date), not angularly sorted -- required for
     * non-star-shaped perimeters to build a valid polygon at all.
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

        List<GhostPerimeterStakeEntity> stakes = GhostPerimeterStakeEntity.findByOwnerCoreInPlacementOrder(serverLevel, core.getUUID());
        if (stakes.size() < 3) {
            player.sendSystemMessage(Component.literal("Place at least 3 perimeter stakes before showing the boundary."));
            return;
        }

        List<Geometry.Polygon.Vertex> coreVertices = new ArrayList<>(stakes.size());
        for (GhostPerimeterStakeEntity stake : stakes) {
            coreVertices.add(new Geometry.Polygon.Vertex((int) Math.floor(stake.getX()), (int) Math.floor(stake.getZ())));
        }
        Geometry.Polygon corePolygon = Geometry.Polygon.coveringBlocks(coreVertices);

        for (Geometry.Polygon.Vertex block : Geometry.Polygon.outerRing(corePolygon)) {
            // Anchored to this point's own local ground height, not the core's fixed Y (fixed
            // 2026-09-26, playtest feedback -- varying terrain along the perimeter made single
            // fixed-height blocks read as scattered floating icons, not a wall). A short vertical
            // stack per point gives a real "wall" silhouette even where the ground itself slopes.
            int groundY = serverLevel.getHeight(Heightmap.Types.WORLD_SURFACE, block.x(), block.z());
            for (int level = 0; level < GhostBoundaryWallEntity.WALL_HEIGHT_BLOCKS; level++) {
                GhostBoundaryWallEntity.create(serverLevel, block.x() + 0.5, groundY + level, block.z() + 0.5, core.getUUID());
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

        if (!PerimeterFit.containsPoint(inputs, core.getX(), core.getZ())) {
            player.sendSystemMessage(Component.literal(
                    "The perimeter stakes don't fully encompass the Town Hall Core yet."));
            return;
        }

        PerimeterFit.FitResult result = PerimeterFit.fit(
                core.getX(), core.getZ(), inputs, PerimeterFit.DEFAULT_TARGET_AREA_BLOCKS, PerimeterFit.DEFAULT_MAX_FIT_RADIUS_BLOCKS);

        for (int i = 0; i < stakes.size(); i++) {
            PerimeterFit.StakeInput fitted = result.fittedStakes().get(i);
            GhostPerimeterStakeEntity stake = stakes.get(i);
            stake.setPos(fitted.x(), stake.getY(), fitted.z());
        }

        registerWithCartographyr(serverLevel, core, result.fittedStakes());
        int stakesCleared = clearPerimeterStakeItems(serverLevel, core);
        core.setFinalized(true);
        core.setActivePerimeterPlanner(null); // Nothing left in-progress -- release the lock (design doc Section 7a; see decisions.md 2026-09-27).
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
    private static void registerWithCartographyr(ServerLevel level, GhostTownHallCoreEntity core, List<PerimeterFit.StakeInput> fittedStakes) {
        // fittedStakes is already in placement order (PerimeterFit.fit preserves whatever order its
        // caller passed in, 2026-09-27 -- see decisions.md same date) -- no re-sort needed here.
        List<Geometry.Polygon.Vertex> realVertices = new ArrayList<>(fittedStakes.size());
        for (PerimeterFit.StakeInput stake : fittedStakes) {
            realVertices.add(new Geometry.Polygon.Vertex((int) Math.floor(stake.x()), (int) Math.floor(stake.z())));
        }
        Geometry.Polygon realPolygon = Geometry.Polygon.coveringBlocks(realVertices);
        registerSettlementCore(level, core, realPolygon);

        Geometry paddedPolygon = Geometry.Polygon.expandedBy(realPolygon, (int) Math.round(CARTOGRAPHYR_PADDING_BLOCKS));

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

    /**
     * DEBUG ONLY -- grants a testing flag on every login, no real crafting recipe exists yet
     * (design doc Section 5 calls for one; not built this milestone). Same pattern/caveats as
     * Lyfe's own debug item grant (LyfeMod#onPlayerLoggedIn) -- must be removed or gated behind a
     * real debug flag before any actual release.
     */
    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) {
            return;
        }
        player.addItem(new ItemStack(ModItems.SETTLEMENT_CLAIM_FLAG.get(), 4));
        LOGGER.info("Granted debug Settlement Claim Flag(s) to {}", player.getName().getString());
    }
}
