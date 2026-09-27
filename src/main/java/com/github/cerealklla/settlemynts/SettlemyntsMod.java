package com.github.cerealklla.settlemynts;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import java.util.ArrayList;
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
import com.github.cerealklla.settlemynts.founding.BoundaryWallLayout;
import com.github.cerealklla.settlemynts.founding.ClientFoundingRequests;
import com.github.cerealklla.settlemynts.founding.FinalizeSettlementPayload;
import com.github.cerealklla.settlemynts.founding.GhostBoundaryWallEntity;
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

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
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

    public SettlemyntsMod(IEventBus modEventBus, ModContainer modContainer) {
        ModItems.ITEMS.register(modEventBus);
        ModEntities.ENTITIES.register(modEventBus);

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
                    if (core != null && core.isTownPlanner(player.getUUID())) {
                        stake.remove(player);
                    }
                });

        registrar.playToServer(FinalizeSettlementPayload.TYPE, FinalizeSettlementPayload.STREAM_CODEC,
                (payload, context) -> finalizeSettlement(payload, context));

        registrar.playToServer(SetBoundaryVisiblePayload.TYPE, SetBoundaryVisiblePayload.STREAM_CODEC,
                (payload, context) -> setBoundaryVisible(payload, context));
    }

    /**
     * Toggles "View Settlement Boundaries" (design doc Section 9). Turning it on discards any
     * existing wall (in case one was left over from a stale state) and generates a fresh one from
     * the settlement's *current* stake positions -- always live, not a snapshot from whenever it
     * was last toggled on. Turning it off just discards every wall point for this core.
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

        List<GhostPerimeterStakeEntity> stakes = GhostPerimeterStakeEntity.findByOwnerCore(serverLevel, core.getUUID());
        if (stakes.size() < 3) {
            player.sendSystemMessage(Component.literal("Place at least 3 perimeter stakes before showing the boundary."));
            return;
        }

        List<PerimeterFit.StakeInput> inputs = new ArrayList<>(stakes.size());
        for (GhostPerimeterStakeEntity stake : stakes) {
            inputs.add(new PerimeterFit.StakeInput(stake.getX(), stake.getZ(), stake.isAbsolute()));
        }
        List<PerimeterFit.StakeInput> ordered = PerimeterFit.sortAngularly(inputs, core.getX(), core.getZ());

        for (BoundaryWallLayout.Point point : BoundaryWallLayout.layout(ordered, BoundaryWallLayout.DEFAULT_SPACING_BLOCKS)) {
            // Anchored to this point's own local ground height, not the core's fixed Y (fixed
            // 2026-09-26, playtest feedback -- varying terrain along the perimeter made single
            // fixed-height blocks read as scattered floating icons, not a wall). A short vertical
            // stack per point gives a real "wall" silhouette even where the ground itself slopes.
            int groundY = serverLevel.getHeight(Heightmap.Types.WORLD_SURFACE, (int) Math.floor(point.x()), (int) Math.floor(point.z()));
            for (int level = 0; level < GhostBoundaryWallEntity.WALL_HEIGHT_BLOCKS; level++) {
                GhostBoundaryWallEntity.create(serverLevel, point.x(), groundY + level, point.z(), core.getUUID());
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

        List<GhostPerimeterStakeEntity> stakes = GhostPerimeterStakeEntity.findByOwnerCore(serverLevel, core.getUUID());
        if (stakes.size() < 3) {
            player.sendSystemMessage(Component.literal("Place at least 3 perimeter stakes before finalizing."));
            return;
        }

        List<PerimeterFit.StakeInput> inputs = new ArrayList<>(stakes.size());
        for (GhostPerimeterStakeEntity stake : stakes) {
            inputs.add(new PerimeterFit.StakeInput(stake.getX(), stake.getZ(), stake.isAbsolute()));
        }

        List<PerimeterFit.StakeInput> orderedForContainment = PerimeterFit.sortAngularly(inputs, core.getX(), core.getZ());
        if (!PerimeterFit.containsPoint(orderedForContainment, core.getX(), core.getZ())) {
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

        player.sendSystemMessage(Component.literal(
                "Perimeter fitted to " + Math.round(result.achievedArea()) + " blocks^2 (target "
                        + Math.round(PerimeterFit.DEFAULT_TARGET_AREA_BLOCKS) + "). Registered with Cartographyr"
                        + (stakesCleared > 0 ? " -- cleared " + stakesCleared + " leftover Perimeter Stake item(s)." : ".")
                        + " Solidifying the core/stakes and enabling protection isn't implemented yet."));
    }

    /**
     * Registers this settlement's fitted perimeter with Cartographyr as a {@code
     * Classification.CONSTRUCTED}/{@code EntityType.SETTLEMENT} entity -- design doc Section 8. The
     * polygon sent is padded {@link #CARTOGRAPHYR_PADDING_BLOCKS} outward from the core along each
     * vertex's own direction (a radial approximation of a uniform buffer, not a true geometric
     * offset -- adequate for the star-shaped-around-the-core polygons this algorithm always
     * produces, not a general-purpose polygon buffer). Idempotent: a settlement already registered
     * (tracked via {@code GhostTownHallCoreEntity#getCartographyrEntityId}) gets its existing
     * entity's geometry *updated* instead of a duplicate being created, so re-running Finalize
     * later (e.g. after adjusting stakes) doesn't leave stale entries behind.
     */
    private static void registerWithCartographyr(ServerLevel level, GhostTownHallCoreEntity core, List<PerimeterFit.StakeInput> fittedStakes) {
        List<PerimeterFit.StakeInput> ordered = PerimeterFit.sortAngularly(fittedStakes, core.getX(), core.getZ());
        List<Geometry.Polygon.Vertex> vertices = new ArrayList<>(ordered.size());
        for (PerimeterFit.StakeInput stake : ordered) {
            double dx = stake.x() - core.getX();
            double dz = stake.z() - core.getZ();
            double distance = Math.hypot(dx, dz);
            double scale = distance > 1.0e-9 ? (distance + CARTOGRAPHYR_PADDING_BLOCKS) / distance : 1.0;
            vertices.add(new Geometry.Polygon.Vertex(
                    (int) Math.round(core.getX() + dx * scale),
                    (int) Math.round(core.getZ() + dz * scale)));
        }
        Geometry paddedPolygon = new Geometry.Polygon(vertices);

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
