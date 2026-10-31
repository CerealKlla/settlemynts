package com.github.cerealklla.settlemynts.debug;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import com.github.cerealklla.cartographyr.api.Cartography;
import com.github.cerealklla.cartographyr.geo.EntityId;
import com.github.cerealklla.cartographyr.geo.GeographicEntity;
import com.github.cerealklla.cartographyr.geo.Geometry;
import com.github.cerealklla.settlemynts.api.Settlemynts;
import com.github.cerealklla.settlemynts.bridge.YconomicsBillBridge;
import com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.guardhouse.GuardEntity;
import com.github.cerealklla.settlemynts.guardhouse.GuardKitStandEntity;
import com.github.cerealklla.settlemynts.guardhouse.GuardKitStandIndex;
import com.github.cerealklla.settlemynts.guardhouse.GuardPendingDeathIndex;
import com.github.cerealklla.settlemynts.registration.ModEntities;
import com.github.cerealklla.settlemynts.roadway.HighwayConnectionMarkerEntity;
import com.github.cerealklla.settlemynts.roadway.HighwayStakeEntity;
import com.github.cerealklla.settlemynts.roadway.HighwayStakeGraph;
import com.github.cerealklla.settlemynts.zone.ContainerWithdraw;
import com.github.cerealklla.settlemynts.zone.GhostPlotStakeEntity;
import com.github.cerealklla.settlemynts.zone.NaturalSettlementPlotStore;
import com.github.cerealklla.settlemynts.zone.NaturalVillagePlotGenerator;
import com.github.cerealklla.settlemynts.zone.PlotRecord;
import com.github.cerealklla.settlemynts.zone.SettlementKey;
import com.github.cerealklla.settlemynts.zone.ShopResource;
import com.github.cerealklla.settlemynts.util.GroundHeightFinder;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.neoforged.fml.ModList;

/**
 * DEBUG ONLY -- deliberately no permission gate, same precedent as Blueprynts'/Lyfe's/Yconomics' own
 * {@code debug.DebugCommands}.
 *
 * <p>{@code /settlemynts plotinfo} -- added 2026-09-30 while chasing a real bug (two disconnected
 * stake chains merging into one degenerate plot polygon, tanking the Lyfe minimap's per-frame
 * outline redraw) -- reports real numbers (live stake count, per-finalized-plot polygon vertex
 * count) for the nearest loaded settlement instead of guessing at them from code review alone.
 */
public final class DebugCommands {

    private static final double NEAREST_CORE_SEARCH_RADIUS = 3.0E7;

    private DebugCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var billWithPeriod = Commands.argument("periodDays", IntegerArgumentType.integer(1))
                .executes(ctx -> registerPlotBill(ctx.getSource(),
                        StringArgumentType.getString(ctx, "plotId"),
                        BlockPosArgument.getLoadedBlockPos(ctx, "sourcePos"),
                        BlockPosArgument.getLoadedBlockPos(ctx, "destPos"),
                        IntegerArgumentType.getInteger(ctx, "qty"),
                        IntegerArgumentType.getInteger(ctx, "periodDays")));
        var billQty = Commands.argument("qty", IntegerArgumentType.integer(1))
                .executes(ctx -> registerPlotBill(ctx.getSource(),
                        StringArgumentType.getString(ctx, "plotId"),
                        BlockPosArgument.getLoadedBlockPos(ctx, "sourcePos"),
                        BlockPosArgument.getLoadedBlockPos(ctx, "destPos"),
                        IntegerArgumentType.getInteger(ctx, "qty"), 1L))
                .then(billWithPeriod);
        var billDestPos = Commands.argument("destPos", BlockPosArgument.blockPos()).then(billQty);
        var billSourcePos = Commands.argument("sourcePos", BlockPosArgument.blockPos()).then(billDestPos);
        var billPlotId = Commands.argument("plotId", StringArgumentType.string()).then(billSourcePos);

        dispatcher.register(Commands.literal("settlemynts")
                .requires(source -> true) // deliberately no permission gate -- see class doc
                .then(Commands.literal("plotinfo")
                        .executes(ctx -> plotInfo(ctx.getSource())))
                .then(Commands.literal("guardinfo")
                        .executes(ctx -> guardInfo(ctx.getSource())))
                .then(Commands.literal("checkavailable")
                        .then(Commands.argument("itemId", StringArgumentType.string())
                                .executes(ctx -> checkAvailable(ctx.getSource(), StringArgumentType.getString(ctx, "itemId")))))
                .then(Commands.literal("watchtowerinfo")
                        .executes(ctx -> watchTowerInfo(ctx.getSource())))
                .then(Commands.literal("naturalvillagers")
                        .executes(ctx -> naturalVillagers(ctx.getSource())))
                .then(Commands.literal("resyncprotection")
                        .executes(ctx -> resyncProtection(ctx.getSource())))
                .then(Commands.literal("resyncshop")
                        .executes(ctx -> resyncShop(ctx.getSource())))
                .then(Commands.literal("resyncnpcprices")
                        .executes(ctx -> resyncNpcShopPrices(ctx.getSource())))
                .then(Commands.literal("fixstuckguards")
                        .executes(ctx -> fixStuckGuards(ctx.getSource())))
                .then(Commands.literal("fixguardhomes")
                        .executes(ctx -> fixGuardHomes(ctx.getSource())))
                .then(Commands.literal("resetplot")
                        // ADMIN ONLY -- mutates live block positions directly (see #resetPlotStructures's
                        // own doc), same gating reasoning as setplotowner below.
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(ctx -> resetPlotStructures(ctx.getSource())))
                .then(Commands.literal("removelisting")
                        .then(Commands.argument("itemId", StringArgumentType.string())
                                .executes(ctx -> removeListing(ctx.getSource(), StringArgumentType.getString(ctx, "itemId")))))
                .then(Commands.literal("highwayinfo")
                        .then(Commands.argument("instanceId", StringArgumentType.string())
                                .executes(ctx -> highwayInfo(ctx.getSource(), StringArgumentType.getString(ctx, "instanceId")))))
                .then(Commands.literal("highwaycleanup")
                        .then(Commands.argument("instanceId", StringArgumentType.string())
                                .executes(ctx -> highwayCleanup(ctx.getSource(), StringArgumentType.getString(ctx, "instanceId")))))
                .then(Commands.literal("highwayhide")
                        .then(Commands.argument("instanceId", StringArgumentType.string())
                                .executes(ctx -> highwayHide(ctx.getSource(), StringArgumentType.getString(ctx, "instanceId")))))
                .then(Commands.literal("entityinfo")
                        .then(Commands.argument("placeId", com.mojang.brigadier.arguments.LongArgumentType.longArg())
                                .executes(ctx -> entityInfo(ctx.getSource(), com.mojang.brigadier.arguments.LongArgumentType.getLong(ctx, "placeId")))))
                .then(Commands.literal("plot")
                        .then(Commands.literal("bill").then(billPlotId)))
                .then(Commands.literal("setplotowner")
                        // ADMIN ONLY -- unlike every other command in this class (see class doc,
                        // deliberately ungated), this one mutates live plot ownership + re-syncs
                        // Protectyons block-edit permissions from it, so it's gated to
                        // LEVEL_GAMEMASTERS (op level 2 -- same convention Yconomics' own
                        // PriceCommands already uses for an admin-only command). RCON's own console
                        // source always passes a permission check, so this covers "rcon/admin only"
                        // either way -- explicit user request, 2026-10-17.
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("owner", StringArgumentType.word())
                                .executes(ctx -> setPlotOwner(ctx.getSource(), StringArgumentType.getString(ctx, "owner"))))));
    }

    /**
     * DEBUG ONLY -- added 2026-10-13, same road-connection quest investigation: the user directly
     * disputed a reported "38 blocks from the settlement edge" distance, having stood at the very
     * first "No Man's Land" tile outside the Town Hall plot specifically when placing the stake --
     * a real, different, smaller boundary (the Town Hall plot's own buffer, {@code
     * PLOT_BUFFER_PADDING_BLOCKS}) than the settlement's own overall registered perimeter ({@code
     * CARTOGRAPHYR_PADDING_BLOCKS}), which is what the Highway anchor check actually measures
     * against. Reports the real polygon bounds for a given Cartographyr place id, plus (if run by a
     * player standing in-world) that player's own real distance to its edge, so this can be checked
     * directly instead of reasoned about from code alone.
     */
    private static int entityInfo(CommandSourceStack source, long placeId) {
        if (!(source.getLevel() instanceof ServerLevel level)) {
            source.sendFailure(Component.literal("Must be run in a real level."));
            return 0;
        }
        Optional<GeographicEntity> entity = Cartography.getEntity(level, new EntityId(placeId));
        if (entity.isEmpty()) {
            source.sendFailure(Component.literal("No Cartographyr entity found for place id " + placeId));
            return 0;
        }
        GeographicEntity ge = entity.get();
        String name = ge.name().orElse("(unnamed)");
        String typeDesc = ge.type() + " / " + ge.classification();
        source.sendSuccess(() -> Component.literal("Place " + placeId + ": \"" + name + "\" (" + typeDesc + ")"), false);
        if (ge.geometry() instanceof Geometry.Polygon polygon) {
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            for (Geometry.Polygon.Vertex v : polygon.vertices()) {
                minX = Math.min(minX, v.x());
                maxX = Math.max(maxX, v.x());
                minZ = Math.min(minZ, v.z());
                maxZ = Math.max(maxZ, v.z());
            }
            int finalMinX = minX, finalMaxX = maxX, finalMinZ = minZ, finalMaxZ = maxZ;
            source.sendSuccess(() -> Component.literal("  bounds: x=[" + finalMinX + ".." + finalMaxX + "] z=[" + finalMinZ + ".." + finalMaxZ
                    + "], " + polygon.vertices().size() + " vertices"), false);
            if (source.getEntity() instanceof net.minecraft.world.entity.player.Player player) {
                BlockPos pos = player.blockPosition();
                double edge = polygon.distanceToNearestEdge(pos.getX(), pos.getZ());
                double outside = polygon.distanceOutside(pos.getX(), pos.getZ());
                source.sendSuccess(() -> Component.literal("  your position " + pos.toShortString()
                        + " -- distanceToNearestEdge=" + edge + " distanceOutside=" + outside), false);
            }
        } else {
            source.sendSuccess(() -> Component.literal("  geometry is not a Polygon: " + ge.geometry()), false);
        }
        return 1;
    }

    /**
     * DEBUG ONLY -- added 2026-10-13 per explicit user request while chasing a real road-connection
     * quest completion bug: lists every stake {@link HighwayStakeGraph} (the persisted,
     * entity-liveness-independent index -- see its own doc) has recorded for a given quest instance
     * id, with its position and which other stakes it's connected to, regardless of whether any of
     * those stakes' chunks are currently loaded. The instance id is the same UUID shown in the
     * quest's own saved data (e.g. via Myssions' quest-instance NBT).
     */
    private static int highwayInfo(CommandSourceStack source, String instanceIdRaw) {
        if (!(source.getLevel() instanceof ServerLevel level)) {
            source.sendFailure(Component.literal("Must be run in a real level."));
            return 0;
        }
        UUID instanceId;
        try {
            instanceId = UUID.fromString(instanceIdRaw);
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal("Not a valid instance id: " + instanceIdRaw));
            return 0;
        }
        List<HighwayStakeGraph.StakeRecord> stakes = HighwayStakeGraph.get(level.getServer()).byInstance(instanceId);
        if (stakes.isEmpty()) {
            source.sendFailure(Component.literal("No persisted Highway Stakes found for instance " + instanceId));
            return 0;
        }
        int total = stakes.size();
        source.sendSuccess(() -> Component.literal(total + " Highway Stake(s) persisted for instance " + instanceId + ":"), false);
        for (HighwayStakeGraph.StakeRecord stake : stakes) {
            boolean loaded = level.getEntity(stake.stakeId()) != null;
            String connections = stake.connections().isEmpty() ? "none"
                    : stake.connections().stream().map(c -> shortId(c.otherStakeId())).reduce((a, b) -> a + ", " + b).orElse("none");
            String line = "  - " + shortId(stake.stakeId()) + " @ " + stake.pos().toShortString()
                    + (loaded ? " [loaded]" : " [not loaded]") + " -> connected to: " + connections;
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return total;
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    /**
     * DEBUG ONLY -- added 2026-10-13 alongside the fix for abandoned/expired Highway Stake quests
     * leaving orphaned stakes/terrain behind in unloaded chunks (see {@code
     * roadway.HighwayStakeEntity#despawnAllForInstance}'s own doc). Manually re-runs that same
     * despawn pass for an already-ended quest instance so existing debris doesn't have to wait for a
     * player to coincidentally walk past every leftover stake before it self-cleans.
     */
    private static int highwayCleanup(CommandSourceStack source, String instanceIdRaw) {
        if (!(source.getLevel() instanceof ServerLevel level)) {
            source.sendFailure(Component.literal("Must be run in a real level."));
            return 0;
        }
        UUID instanceId;
        try {
            instanceId = UUID.fromString(instanceIdRaw);
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal("Not a valid instance id: " + instanceIdRaw));
            return 0;
        }
        List<HighwayStakeGraph.StakeRecord> before = HighwayStakeGraph.get(level.getServer()).byInstance(instanceId);
        if (before.isEmpty()) {
            source.sendFailure(Component.literal("No persisted Highway Stakes found for instance " + instanceId));
            return 0;
        }
        BlockPos hint = before.get(0).pos();
        HighwayStakeEntity.despawnAllForInstance(level, instanceId, hint);
        int remaining = HighwayStakeGraph.get(level.getServer()).byInstance(instanceId).size();
        int cleaned = before.size() - remaining;
        source.sendSuccess(() -> Component.literal("Cleaned up " + cleaned + "/" + before.size()
                + " stake(s) for instance " + instanceId + (remaining > 0
                ? (" -- " + remaining + " still in unloaded chunks, marked to self-clean on next load.")
                : " -- all done.")), false);
        return cleaned;
    }

    /**
     * DEBUG ONLY -- added 2026-10-13 alongside the real hide-on-completion fix: an already-completed
     * road quest instance (status flipped to AWAITING_REWARD_PICKUP under the old code) will never
     * re-run {@code RoadQuestTicker#finalizeConnection}, so the new auto-hide never fires for it
     * retroactively. Manually triggers the same no-terrain-revert hide path for one instance id.
     */
    private static int highwayHide(CommandSourceStack source, String instanceIdRaw) {
        if (!(source.getLevel() instanceof ServerLevel level)) {
            source.sendFailure(Component.literal("Must be run in a real level."));
            return 0;
        }
        UUID instanceId;
        try {
            instanceId = UUID.fromString(instanceIdRaw);
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal("Not a valid instance id: " + instanceIdRaw));
            return 0;
        }
        List<HighwayStakeGraph.StakeRecord> before = HighwayStakeGraph.get(level.getServer()).byInstance(instanceId);
        if (before.isEmpty()) {
            source.sendFailure(Component.literal("No persisted Highway Stakes found for instance " + instanceId));
            return 0;
        }
        BlockPos hint = before.get(0).pos();
        HighwayStakeEntity.hideAllForInstance(level, instanceId, hint);
        HighwayConnectionMarkerEntity.discardAll(level, instanceId, hint);
        int remaining = HighwayStakeGraph.get(level.getServer()).byInstance(instanceId).size();
        int hidden = before.size() - remaining;
        source.sendSuccess(() -> Component.literal("Hid " + hidden + "/" + before.size()
                + " stake(s) for instance " + instanceId + " (no terrain revert)" + (remaining > 0
                ? (" -- " + remaining + " still in unloaded chunks, marked to self-clean on next load.")
                : " -- all done.")), false);
        return hidden;
    }

    /**
     * DEBUG ONLY -- added 2026-10-12, real report: "I'm the mayor but it's not letting me modify any
     * plots in my settlement," confirmed live to be a block-break/place denial (Protectyons), not a
     * UI permission gate -- the UI-level {@code PlotPermissions#canManage} check and the founder's
     * own recorded identity both checked out correctly against the real live data, so the most likely
     * remaining explanation is Protectyons' own separate {@code permission.ProtectedAreaRegistry}
     * having drifted out of sync with Settlemynts' own source of truth for some plot, for a reason
     * not pinned down. Rather than keep guessing, this re-pushes every already-registered area's
     * permitted-player set fresh (both every finalized plot and the settlement-wide area), the same
     * resync {@code GrantTownPlannerPayload}'s handler already does as a side effect -- safe to run
     * any time, a no-op if everything was already correct.
     */
    private static int resyncProtection(CommandSourceStack source) {
        if (!(source.getEntity() instanceof net.minecraft.world.entity.player.Player player) || !(source.getLevel() instanceof ServerLevel level)) {
            source.sendFailure(Component.literal("Must be run by a player in-world."));
            return 0;
        }
        if (!ModList.get().isLoaded("protectyons")) {
            source.sendFailure(Component.literal("Protectyons isn't loaded -- nothing to resync."));
            return 0;
        }
        AABB worldBounds = new AABB(-NEAREST_CORE_SEARCH_RADIUS, level.getMinY(), -NEAREST_CORE_SEARCH_RADIUS,
                NEAREST_CORE_SEARCH_RADIUS, level.getMaxY(), NEAREST_CORE_SEARCH_RADIUS);
        List<GhostTownHallCoreEntity> cores = level.getEntities(ModEntities.GHOST_TOWN_HALL_CORE.get(), worldBounds, c -> true);
        GhostTownHallCoreEntity nearest = null;
        double bestDistSq = Double.MAX_VALUE;
        for (GhostTownHallCoreEntity core : cores) {
            double distSq = core.distanceToSqr(player);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                nearest = core;
            }
        }
        if (nearest == null) {
            source.sendFailure(Component.literal("No loaded Town Hall Core found."));
            return 0;
        }
        // Real root cause found live, 2026-10-12: this used to call updatePermittedPlayers, which
        // is a documented no-op against an areaId that was never actually registered -- and it turns
        // out most of this settlement's plots (and/or the settlement-wide area) genuinely never were
        // (confirmed via temporary debug logging in Protectyons: only 1 of 11 expected areas existed
        // in its registry at all). Real cause of THAT still unconfirmed (not persisting across
        // restarts is the leading theory, but unproven) -- this command now does a real re-register
        // instead, recreating any missing entry outright, using each plot/settlement's own
        // already-correct geometry pulled fresh from Cartographyr (confirmed intact and correct via
        // the same debug session) rather than recomputing it from scratch.
        int count = 0;
        for (PlotRecord plot : nearest.getPlots()) {
            java.util.Optional<com.github.cerealklla.cartographyr.geo.Geometry.Polygon> polygon =
                    com.github.cerealklla.settlemynts.api.Settlemynts.resolvePolygonDirect(level, plot);
            if (polygon.isEmpty()) {
                continue;
            }
            com.github.cerealklla.settlemynts.bridge.ProtectyonsPlotBridge.registerPlot(level, plot.plotId(), polygon.get(),
                    com.github.cerealklla.settlemynts.zone.PlotPermissions.computeProtectionPermittedPlayers(plot, nearest));
            count++;
        }
        boolean settlementResynced = false;
        if (nearest.isFinalized() && nearest.getCartographyrEntityId() != null) {
            var settlementEntity = com.github.cerealklla.cartographyr.api.Cartography.getEntity(level,
                    new com.github.cerealklla.cartographyr.geo.EntityId(nearest.getCartographyrEntityId()));
            if (settlementEntity.isPresent()) {
                com.github.cerealklla.settlemynts.bridge.ProtectyonsPlotBridge.registerSettlement(level, nearest.getUUID(),
                        settlementEntity.get().geometry(), nearest.getTownPlanners());
                settlementResynced = true;
            }
        }
        int finalCount = count;
        boolean finalSettlementResynced = settlementResynced;
        GhostTownHallCoreEntity finalCore = nearest;
        source.sendSuccess(() -> Component.literal("Re-registered Protectyons permissions for \"" + finalCore.getSettlementName()
                + "\" -- " + finalCount + "/" + finalCore.getPlots().size() + " plot(s), settlement-wide area "
                + (finalSettlementResynced ? "re-registered" : "SKIPPED (not finalized or no Cartographyr entity)") + "."), false);
        return count;
    }

    /**
     * DEBUG ONLY -- added 2026-10-08 to directly check a real user report ("I don't think a single
     * NPC has taken a job out of 4 towns") instead of guessing at vanilla profession-claiming timing
     * from code review alone. For every known natural settlement, reports total Villager count, how
     * many have claimed a real profession (i.e. not {@code VillagerProfession.NONE}) and what they
     * are, and how many are tagged with {@link NaturalVillagePlotGenerator#PLOT_ID_TAG} (the thing
     * that actually lets them open the shared Shop) -- see {@code zone.NaturalVillageShopLinkTicker}.
     * This mod never assigns a profession itself; if this command shows 0 villagers with a real
     * profession, that's vanilla's own job-site-claiming AI not having claimed anything yet, not a
     * bug here.
     */
    private static int naturalVillagers(CommandSourceStack source) {
        if (!(source.getLevel() instanceof ServerLevel level)) {
            source.sendFailure(Component.literal("Must be run in a real level."));
            return 0;
        }
        Map<SettlementKey, List<PlotRecord>> allSettlements = NaturalSettlementPlotStore.get(level.getServer()).all();
        if (allSettlements.isEmpty()) {
            source.sendFailure(Component.literal("No natural-village plots tracked yet."));
            return 0;
        }
        for (Map.Entry<SettlementKey, List<PlotRecord>> entry : allSettlements.entrySet()) {
            Optional<GeographicEntity> settlementEntity = Cartography.getEntity(level, new EntityId(entry.getKey().settlementEntityId()));
            if (settlementEntity.isEmpty() || !(settlementEntity.get().geometry() instanceof Geometry.Polygon polygon) || polygon.vertices().isEmpty()) {
                source.sendSuccess(() -> Component.literal("Settlement " + entry.getKey() + ": no loaded Cartographyr entity."), false);
                continue;
            }
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            for (Geometry.Polygon.Vertex vertex : polygon.vertices()) {
                minX = Math.min(minX, vertex.x());
                maxX = Math.max(maxX, vertex.x());
                minZ = Math.min(minZ, vertex.z());
                maxZ = Math.max(maxZ, vertex.z());
            }
            AABB bounds = new AABB(minX, level.getMinY(), minZ, maxX, level.getMaxY(), maxZ);
            List<net.minecraft.world.entity.npc.villager.Villager> villagers =
                    level.getEntitiesOfClass(net.minecraft.world.entity.npc.villager.Villager.class, bounds,
                            v -> v.getClass() == net.minecraft.world.entity.npc.villager.Villager.class);
            int withProfession = 0;
            int tagged = 0;
            StringBuilder professions = new StringBuilder();
            for (net.minecraft.world.entity.npc.villager.Villager villager : villagers) {
                var profession = villager.getVillagerData().profession();
                boolean hasJob = !profession.is(net.minecraft.world.entity.npc.villager.VillagerProfession.NONE);
                if (hasJob) {
                    withProfession++;
                    professions.append(profession.unwrapKey().map(k -> k.identifier().getPath()).orElse("?")).append(", ");
                }
                if (villager.getPersistentData().contains(NaturalVillagePlotGenerator.PLOT_ID_TAG)) {
                    tagged++;
                }
            }
            String plotSummary = entry.getValue().size() + " plot(s): " + entry.getValue().stream()
                    .map(p -> p.name()).reduce((a, b) -> a + ", " + b).orElse("none");
            int villagerCount = villagers.size();
            int finalWithProfession = withProfession;
            int finalTagged = tagged;
            String professionList = professions.toString();
            source.sendSuccess(() -> Component.literal("Settlement " + entry.getKey() + " (" + plotSummary + "): "
                    + villagerCount + " villager(s), " + finalWithProfession + " with a claimed profession ["
                    + professionList + "], " + finalTagged + " tagged for shop access."), false);
        }
        return 1;
    }

    /**
     * DEBUG ONLY -- a minimal manual trigger for the Settlemynts -> Yconomics recurring-bill
     * integration (design doc Section 14a follow-up, 2026-10-05, see decisions.md), standing in
     * for a future Plot Config Sign UI. {@code sourcePos}/{@code destPos} must each already be a
     * real tracked storage box (resolved via {@code Cartography.getBoxIdAt}) -- this command
     * doesn't place or validate boxes itself. No permission check (same "debug commands skip
     * PlotPermissions" precedent as every other command in this class) -- a real UI would gate
     * this on {@code zone.PlotPermissions#canManage}.
     */
    private static int registerPlotBill(CommandSourceStack source, String plotIdRaw, BlockPos sourcePos, BlockPos destPos, int qty, long periodDays) {
        if (!YconomicsBillBridge.isAvailable()) {
            source.sendFailure(Component.literal("Yconomics isn't loaded -- recurring bills aren't available."));
            return 0;
        }
        if (!(source.getLevel() instanceof ServerLevel level)) {
            source.sendFailure(Component.literal("Must be run in a real level."));
            return 0;
        }
        UUID plotId;
        try {
            plotId = UUID.fromString(plotIdRaw);
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal("Not a valid plot id: " + plotIdRaw));
            return 0;
        }

        Optional<UUID> sourceBoxId = Cartography.getBoxIdAt(level, sourcePos);
        if (sourceBoxId.isEmpty()) {
            source.sendFailure(Component.literal("No tracked storage box at " + sourcePos.toShortString()));
            return 0;
        }
        Optional<UUID> destBoxId = Cartography.getBoxIdAt(level, destPos);
        if (destBoxId.isEmpty()) {
            source.sendFailure(Component.literal("No tracked storage box at " + destPos.toShortString()));
            return 0;
        }

        Identifier goldNugget = BuiltInRegistries.ITEM.getKey(Items.GOLD_NUGGET);
        YconomicsBillBridge.BoxRef sourceBox = new YconomicsBillBridge.BoxRef(sourceBoxId.get(), GlobalPos.of(level.dimension(), sourcePos));
        YconomicsBillBridge.BoxRef destBox = new YconomicsBillBridge.BoxRef(destBoxId.get(), GlobalPos.of(level.dimension(), destPos));

        UUID billId = YconomicsBillBridge.registerPlotBill(level, plotId, List.of(sourceBox), List.of(destBox),
                java.util.Map.of(goldNugget, qty), periodDays);
        source.sendSuccess(() -> Component.literal("Registered rent bill " + billId + " for plot " + plotId
                + " (" + qty + " gold nugget(s) every " + periodDays + " day(s))."), true);
        return 1;
    }

    /**
     * ADMIN ONLY (LEVEL_GAMEMASTERS, or RCON) -- added 2026-10-17 per explicit user request, after a real
     * bug ({@code zone.ShopSeeding}'s free-wood catalog fix, same date) left a plot that needed its
     * Owner reset by hand. Resolves the target plot as whichever finalized plot the command's own
     * player-entity is currently standing inside (same "nearest core, then contains-check" shape
     * {@link #plotInfo}/{@link #resyncProtection} already use) -- there's no other existing way to
     * address a plot by id from a command, and "stand in it" is simplest for an admin fixing a
     * specific plot live. {@code owner} is either an online player's exact name, or the literal
     * {@code clear}/{@code npc} to make the plot NPC-owned again (same meaning as a plot that never
     * had one) -- an offline owner isn't resolvable, same limitation Finalize's own Owner field has.
     *
     * <p>Re-syncs Protectyons' block-edit permissions from the new owner (same computation Finalize
     * itself uses, {@code PlotPermissions#computeProtectionPermittedPlayers}) so build access
     * actually follows the change. Deliberately does NOT retroactively create or cancel a recurring
     * rent bill -- that's a separate system ({@code bridge.YconomicsBillBridge}) with its own real
     * box state to resolve, out of scope for a simple ownership correction; said so explicitly in the
     * success message so an admin doesn't assume one was set up.
     */
    private static int setPlotOwner(CommandSourceStack source, String ownerArg) {
        if (!(source.getEntity() instanceof net.minecraft.world.entity.player.Player player) || !(source.getLevel() instanceof ServerLevel level)) {
            source.sendFailure(Component.literal("Must be run by a player in-world (stand inside the target plot)."));
            return 0;
        }
        AABB worldBounds = new AABB(-NEAREST_CORE_SEARCH_RADIUS, level.getMinY(), -NEAREST_CORE_SEARCH_RADIUS,
                NEAREST_CORE_SEARCH_RADIUS, level.getMaxY(), NEAREST_CORE_SEARCH_RADIUS);
        BlockPos pos = player.blockPosition();
        GhostTownHallCoreEntity foundCore = null;
        PlotRecord foundPlot = null;
        for (GhostTownHallCoreEntity core : level.getEntities(ModEntities.GHOST_TOWN_HALL_CORE.get(), worldBounds, GhostTownHallCoreEntity::isFinalized)) {
            Optional<PlotRecord> plot = com.github.cerealklla.settlemynts.zone.PlotGeometry.findContainingPlot(level, core, pos.getX(), pos.getZ());
            if (plot.isPresent()) {
                foundCore = core;
                foundPlot = plot.get();
                break;
            }
        }
        if (foundPlot == null) {
            source.sendFailure(Component.literal("You're not standing inside any finalized plot."));
            return 0;
        }

        Optional<UUID> newOwner;
        if (ownerArg.equalsIgnoreCase("clear") || ownerArg.equalsIgnoreCase("npc")) {
            newOwner = Optional.empty();
        } else {
            net.minecraft.server.level.ServerPlayer target = level.getServer().getPlayerList().getPlayerByName(ownerArg);
            if (target == null) {
                source.sendFailure(Component.literal("No online player named \"" + ownerArg
                        + "\" (an offline owner isn't resolvable -- same limitation as Finalize's own Owner field). Use \"clear\" to make it NPC-owned."));
                return 0;
            }
            newOwner = Optional.of(target.getUUID());
        }

        PlotRecord updated = foundPlot.withOwner(newOwner);
        foundCore.updatePlot(updated);

        if (ModList.get().isLoaded("protectyons")) {
            java.util.Set<UUID> permitted = com.github.cerealklla.settlemynts.zone.PlotPermissions.computeProtectionPermittedPlayers(updated, foundCore);
            com.github.cerealklla.settlemynts.api.Settlemynts.resolvePolygonDirect(level, updated).ifPresent(polygon ->
                    com.github.cerealklla.settlemynts.bridge.ProtectyonsPlotBridge.registerPlot(level, updated.plotId(), polygon, permitted));
        }

        String label = newOwner.isPresent() ? ownerArg : "NPC-owned (no owner)";
        source.sendSuccess(() -> Component.literal("Plot \"" + updated.name() + "\" owner set to " + label
                + ". (This does not create/cancel a recurring rent bill -- that's unaffected.)"), true);
        return 1;
    }

    /**
     * DEBUG ONLY -- added 2026-10-21, real report: a Blacksmith plot already at the right Tier never
     * picked up the Nature Staff catalog entry added the same day (see {@code
     * structure.ShopSeedCatalogs} in Lyfe). Root cause isn't stale Planned Inventory, as first
     * suspected -- {@code zone.ShopSeeding#applyTieredDefaults} genuinely re-derives/merges targets
     * on every real Tier increase. The actual gap: that same method is guarded on {@code tier >
     * plot.tieredDefaultsAppliedTier()}, so a plot that already had its *current* tier's defaults
     * applied (even before the catalog itself changed) will never re-notice a catalog-content change
     * at that same tier -- only a genuine new tier-crossing re-triggers it. This resets that one
     * plot's {@code tieredDefaultsAppliedTier} back to 0 and immediately re-runs {@code
     * ShopSeeding#syncToCatalog} (the exact same path "Enter/Manage Shop" already calls on open),
     * which re-derives and persists fresh listings/Planned Inventory targets against the catalog as
     * it stands right now. Resolves the target plot the same "stand inside it" way {@link
     * #setPlotOwner} does.
     */
    private static int resyncShop(CommandSourceStack source) {
        if (!(source.getEntity() instanceof net.minecraft.world.entity.player.Player player) || !(source.getLevel() instanceof ServerLevel level)) {
            source.sendFailure(Component.literal("Must be run by a player in-world (stand inside the target plot)."));
            return 0;
        }
        AABB worldBounds = new AABB(-NEAREST_CORE_SEARCH_RADIUS, level.getMinY(), -NEAREST_CORE_SEARCH_RADIUS,
                NEAREST_CORE_SEARCH_RADIUS, level.getMaxY(), NEAREST_CORE_SEARCH_RADIUS);
        BlockPos pos = player.blockPosition();
        GhostTownHallCoreEntity foundCore = null;
        PlotRecord foundPlot = null;
        for (GhostTownHallCoreEntity core : level.getEntities(ModEntities.GHOST_TOWN_HALL_CORE.get(), worldBounds, GhostTownHallCoreEntity::isFinalized)) {
            Optional<PlotRecord> plot = com.github.cerealklla.settlemynts.zone.PlotGeometry.findContainingPlot(level, core, pos.getX(), pos.getZ());
            if (plot.isPresent()) {
                foundCore = core;
                foundPlot = plot.get();
                break;
            }
        }
        if (foundPlot == null) {
            source.sendFailure(Component.literal("You're not standing inside any finalized plot."));
            return 0;
        }
        if (foundPlot.shopId().isEmpty()) {
            source.sendFailure(Component.literal("Plot \"" + foundPlot.name() + "\" has no Shop yet -- nothing to resync."));
            return 0;
        }
        int previousAppliedTier = foundPlot.tieredDefaultsAppliedTier();
        PlotRecord resetPlot = foundPlot.withTieredDefaultsAppliedTier(0);
        BlockPos anchor = resetPlot.boxPos().orElse(pos);
        // Real bug found 2026-10-20: for an NPC-owned plot (owner().isEmpty()), ShopSeeding#syncToCatalog
        // calls applyCatalogFull and then immediately REFETCHES the plot fresh from foundCore's own live
        // list before calling applyTieredDefaults -- which silently discarded this reset, since it only
        // ever existed on this local `resetPlot` copy, never actually persisted. The refetched copy still
        // had the real (un-reset) tieredDefaultsAppliedTier, so applyTieredDefaults's own gate
        // (`tier <= tieredDefaultsAppliedTier()`) kept returning immediately every single time this
        // command ran, for a Blacksmith plot or anything else with no owner -- confirmed directly: zero
        // [ShopSeedDiag] log lines ever appeared despite several real runs. Fixed by persisting the reset
        // through the owner first, so syncToCatalog's own refetch sees it too.
        foundCore.updatePlot(resetPlot);
        com.github.cerealklla.settlemynts.zone.ShopSeeding.syncToCatalog(level, resetPlot, foundCore, anchor);
        String plotName = foundPlot.name();
        source.sendSuccess(() -> Component.literal("Re-applied tiered catalog defaults for \"" + plotName
                + "\" (was stuck at applied-tier " + previousAppliedTier
                + ") -- any newly-added catalog listings/Planned Inventory targets should now show up."), true);

        // Temporary diagnostic, 2026-10-20 (real report: this command has reported success 5 times
        // in a row with nothing actually changing in the real Shop) -- directly compares the
        // catalog's own current recommendation set against what's really listed/suppressed, instead
        // of trusting the "success" message above. Remove once the real gap is found.
        UUID shopId = foundPlot.shopId().get();
        UUID targetPlotId = foundPlot.plotId();
        PlotRecord refreshed = foundCore.getPlots().stream()
                .filter(p -> p.plotId().equals(targetPlotId)).findFirst().orElse(foundPlot);
        java.util.Set<Identifier> catalogIds = new java.util.HashSet<>();
        for (com.github.cerealklla.settlemynts.zone.SeedListing seed
                : com.github.cerealklla.settlemynts.zone.ShopSeeding.catalogSeedListings(level, refreshed, anchor)) {
            seed.resource().flatMap(com.github.cerealklla.settlemynts.zone.ShopResource::itemId).ifPresent(catalogIds::add);
        }
        java.util.Set<Identifier> configuredIds = new java.util.HashSet<>();
        for (com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge.ShopListingView view
                : com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge.getListings(level, shopId)) {
            view.resource().itemId().ifPresent(configuredIds::add);
        }
        java.util.Set<Identifier> suppressedIds = new java.util.HashSet<>();
        for (com.github.cerealklla.settlemynts.zone.SuppressedShopResource s : refreshed.suppressedShopResources()) {
            if (!s.isTag()) {
                suppressedIds.add(s.resourceKey());
            }
        }
        List<Identifier> missing = catalogIds.stream().filter(id -> !configuredIds.contains(id)).sorted(Identifier::compareTo).toList();
        source.sendSuccess(() -> Component.literal("[diag] catalog=" + catalogIds.size() + " configured=" + configuredIds.size()
                + " suppressed=" + suppressedIds.size() + " missingFromRealShop=" + missing.size()), false);
        for (Identifier id : missing) {
            boolean suppressed = suppressedIds.contains(id);
            source.sendSuccess(() -> Component.literal("  - " + id + (suppressed ? " (SUPPRESSED)" : " (not suppressed -- should have just been added)")), false);
        }
        return 1;
    }

    /**
     * One-time data fix, 2026-10-28 -- real report: "why would an armorer be asking for Gold and
     * Iron ingots, but only paying 2g for them?" Confirmed live via {@code /yconomics derivedprice}:
     * the recipe-derivation engine correctly computes both at 62 gold today, so this is NOT a
     * derivation bug -- it's the same shape of staleness {@link #resyncShop} already exists for
     * (player-founded plots), just for natural villages instead: {@code zone.ShopSeeding
     * #effectivePrice}'s own doc confirms a listing's price is only ever set ONCE, at seed time, and
     * is deliberately never retroactively touched afterward (so a manually-customized price is never
     * silently overwritten) -- these two listings were evidently seeded before this server's
     * recipe-derived pricing had a real value for them, and nothing has corrected them since.
     *
     * <p>Scoped to natural villages only ({@link NaturalSettlementPlotStore}, trivially scannable
     * whole-server with no "stand inside the plot" requirement) since that's what's actually
     * confirmed affected ("Starter Armorer") -- a player-founded settlement's own NPC-owned shops
     * aren't touched by this pass; {@link #resyncShop} is the per-plot tool for those if the same
     * staleness ever surfaces there. Only ever touches a listing on a plot with no real owner
     * ({@link PlotRecord#owner()} empty) -- a player-owned shop's price is that owner's own choice,
     * never silently overwritten.
     */
    private static int resyncNpcShopPrices(CommandSourceStack source) {
        if (!(source.getLevel() instanceof ServerLevel level)) {
            source.sendFailure(Component.literal("Must be run in a real level."));
            return 0;
        }
        if (!YconomicsShopBridge.isAvailable()) {
            source.sendFailure(Component.literal("Yconomics isn't loaded -- nothing to resync."));
            return 0;
        }
        int[] checked = {0};
        int[] updated = {0};
        for (List<PlotRecord> plots : NaturalSettlementPlotStore.get(level.getServer()).all().values()) {
            for (PlotRecord plot : plots) {
                if (plot.owner().isPresent() || plot.shopId().isEmpty()) {
                    continue;
                }
                UUID shopId = plot.shopId().get();
                for (YconomicsShopBridge.ShopListingView listing : YconomicsShopBridge.getListings(level, shopId)) {
                    checked[0]++;
                    Optional<Identifier> itemId = listing.resource().itemId();
                    if (itemId.isEmpty()) {
                        continue;
                    }
                    Optional<Integer> derived = YconomicsShopBridge.getDerivedPrice(level, itemId.get());
                    if (derived.isPresent() && derived.get() != listing.pricePerUnit()) {
                        YconomicsShopBridge.setListingPrice(level, shopId, listing.resource(), derived.get());
                        updated[0]++;
                    }
                }
            }
        }
        int finalChecked = checked[0];
        int finalUpdated = updated[0];
        source.sendSuccess(() -> Component.literal("Resynced " + finalUpdated + "/" + finalChecked
                + " NPC shop listing(s) across every tracked natural village to current recipe-derived prices."), true);
        return finalUpdated;
    }

    /**
     * Forcibly removes one real Shop listing from whichever plot the player is standing in --
     * real need, 2026-10-20: a Grocer catalog bug (see {@code lyfe.structure.ShopSeedCatalogs
     * #grocerListings}' own fix) had already listed Gold Nugget/Ingot/Block for sale at a flat
     * floor price before the catalog fix shipped -- a severe live exploit (an Ingot sold for 2 gold
     * is worth 9 Nuggets melted down). The catalog fix alone only stops a *future* application from
     * re-adding a listing; every catalog-driven apply path in this mod is additive-only by design
     * (see {@code zone.ShopSeeding}'s own doc), so an already-created bad listing needs a direct,
     * one-time removal rather than new generic removal-sweep code -- this command is that removal
     * tool, generically useful for the same situation recurring with a different item later. Checks
     * both a player-founded settlement's real plot (via its {@code GhostTownHallCoreEntity}) and a
     * natural village's plot (via {@code NaturalSettlementPlotStore}, which has no core entity at
     * all to search through) -- the Grocer this was built for is a natural-village shop.
     */
    private static int removeListing(CommandSourceStack source, String itemIdRaw) {
        if (!(source.getEntity() instanceof net.minecraft.world.entity.player.Player player) || !(source.getLevel() instanceof ServerLevel level)) {
            source.sendFailure(Component.literal("Must be run by a player in-world (stand inside the target plot)."));
            return 0;
        }
        Identifier itemId = Identifier.tryParse(itemIdRaw);
        if (itemId == null) {
            source.sendFailure(Component.literal("Not a valid item id: " + itemIdRaw));
            return 0;
        }
        BlockPos pos = player.blockPosition();
        PlotRecord foundPlot = null;

        AABB worldBounds = new AABB(-NEAREST_CORE_SEARCH_RADIUS, level.getMinY(), -NEAREST_CORE_SEARCH_RADIUS,
                NEAREST_CORE_SEARCH_RADIUS, level.getMaxY(), NEAREST_CORE_SEARCH_RADIUS);
        for (GhostTownHallCoreEntity core : level.getEntities(ModEntities.GHOST_TOWN_HALL_CORE.get(), worldBounds, GhostTownHallCoreEntity::isFinalized)) {
            Optional<PlotRecord> plot = com.github.cerealklla.settlemynts.zone.PlotGeometry.findContainingPlot(level, core, pos.getX(), pos.getZ());
            if (plot.isPresent()) {
                foundPlot = plot.get();
                break;
            }
        }
        if (foundPlot == null) {
            var store = com.github.cerealklla.settlemynts.zone.NaturalSettlementPlotStore.get(level.getServer());
            outer:
            for (var entry : store.all().entrySet()) {
                for (PlotRecord plot : entry.getValue()) {
                    Optional<GeographicEntity> entity = Cartography.getEntity(level, new EntityId(plot.cartographyrPlotEntityId()));
                    if (entity.isPresent() && entity.get().geometry() instanceof Geometry.Polygon polygon
                            && polygon.contains(pos.getX(), pos.getZ())) {
                        foundPlot = plot;
                        break outer;
                    }
                }
            }
        }
        if (foundPlot == null) {
            source.sendFailure(Component.literal("You're not standing inside any finalized plot."));
            return 0;
        }
        if (foundPlot.shopId().isEmpty()) {
            source.sendFailure(Component.literal("Plot \"" + foundPlot.name() + "\" has no Shop -- nothing to remove."));
            return 0;
        }
        com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge.removeListing(level, foundPlot.shopId().get(),
                com.github.cerealklla.settlemynts.zone.ShopResource.ofItem(itemId));
        String plotName = foundPlot.name();
        source.sendSuccess(() -> Component.literal("Removed listing \"" + itemId + "\" from \"" + plotName + "\"'s Shop (if it existed)."), true);
        return 1;
    }

    /**
     * One-time data fix, 2026-10-29 -- real report: several Guard Kit Stands showed "Ready to spawn"
     * despite their guard having genuinely died already. Root cause: these guards died under the old
     * pre-{@link GuardPendingDeathIndex} regime (see that class's own doc for the full incident),
     * which could permanently leave a stand's {@code assignedGuardId} pointing at a now-gone entity
     * with no pending-death record ever created to let {@code GuardSpawnTicker} notice and clear it --
     * that ticker's own "already alive" check short-circuits forever once {@code assignedGuardId} is
     * present, regardless of whether the entity it points to still exists. The fix shipped the same
     * day only makes future deaths reliable; it can't retroactively repair a stand already stuck in
     * this state, so this is a manual, one-time sweep rather than new permanent self-healing logic in
     * the ticker itself.
     *
     * <p>Deliberately re-checks every server dimension (not just the stand's own level) before
     * concluding a guard is really gone -- the same cross-dimension gap that made an earlier, reverted
     * attempt at this fix unsound (see {@code GuardPendingDeathIndex}'s own doc): a guard genuinely
     * alive in a different dimension (e.g. pathing home via {@code GuardReturnToDimensionGoal}) must
     * never be misreported as dead here. Also skips any stand that already has a pending death record
     * -- {@code GuardSpawnTicker} will clear those on its own very shortly, no intervention needed.
     */
    private static int fixStuckGuards(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        int checked = 0;
        int fixed = 0;
        for (Map.Entry<UUID, GlobalPos> entry : GuardKitStandIndex.get(server).all().entrySet()) {
            GlobalPos pos = entry.getValue();
            ServerLevel level = server.getLevel(pos.dimension());
            if (level == null || !(level.getEntity(entry.getKey()) instanceof GuardKitStandEntity stand)
                    || stand.assignedGuardId().isEmpty()) {
                continue;
            }
            checked++;
            UUID guardId = stand.assignedGuardId().get();
            boolean trulyAlive = false;
            for (ServerLevel candidate : server.getAllLevels()) {
                if (candidate.getEntity(guardId) instanceof LivingEntity guard && guard.isAlive()) {
                    trulyAlive = true;
                    break;
                }
            }
            if (trulyAlive || GuardPendingDeathIndex.get(server).peek(entry.getKey()).isPresent()) {
                continue;
            }
            stand.clearAssignedGuardId();
            stand.setRespawnReadyAtGameTime(level.getGameTime());
            fixed++;
        }
        int finalChecked = checked;
        int finalFixed = fixed;
        source.sendSuccess(() -> Component.literal("Checked " + finalChecked + " Guard Kit Stand(s) presumed to have a live guard -- "
                + finalFixed + " were actually dead in every dimension with no pending respawn record, now cleared and ready to respawn."), true);
        return fixed;
    }

    /**
     * One-time data fix, 2026-10-29 -- real report: a Nether settlement's guard that had legitimately
     * crossed into the Overworld (chasing/healing) just stood there instead of pathing back. Root
     * cause: {@code GuardReturnToDimensionGoal}'s own real bug fix shipped earlier the same day --
     * see that class's own doc -- added a one-time migration for a guard with no persisted {@code
     * homeDimension} yet, but its first version lazily adopted *whichever dimension the guard was
     * already in* as home. That's wrong for a guard that happened to already be astray at the exact
     * moment the migration ran (this one, specifically), permanently mislearning "home" as the
     * Overworld it merely happened to be standing in when the fix first shipped. The goal's own
     * migration logic was corrected the same day to resolve the real home from {@link
     * GuardKitStandIndex} instead of guessing -- but that correction only helps a guard whose {@code
     * homeDimension} is still {@code null}; this guard's was already (wrongly) set by the time that
     * correction shipped, so it needs this one-time manual repair rather than a second round of
     * self-healing migration logic.
     *
     * <p>Deliberately re-checks every server dimension (not just the stand's own level) to find the
     * live guard itself, same reasoning {@link #fixStuckGuards} already established -- a guard that
     * successfully crossed dimensions is, by definition, not in its stand's own level right now.
     */
    private static int fixGuardHomes(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        int checked = 0;
        int fixed = 0;
        for (Map.Entry<UUID, GlobalPos> entry : GuardKitStandIndex.get(server).all().entrySet()) {
            GlobalPos standPos = entry.getValue();
            ServerLevel standLevel = server.getLevel(standPos.dimension());
            if (standLevel == null || !(standLevel.getEntity(entry.getKey()) instanceof GuardKitStandEntity stand)
                    || stand.assignedGuardId().isEmpty()) {
                continue;
            }
            GuardEntity guard = null;
            for (ServerLevel candidate : server.getAllLevels()) {
                if (candidate.getEntity(stand.assignedGuardId().get()) instanceof GuardEntity found) {
                    guard = found;
                    break;
                }
            }
            if (guard == null) {
                continue;
            }
            checked++;
            if (standPos.dimension().equals(guard.homeDimension())) {
                continue;
            }
            guard.setHomeDimension(standPos.dimension());
            fixed++;
        }
        int finalChecked = checked;
        int finalFixed = fixed;
        source.sendSuccess(() -> Component.literal("Checked " + finalChecked + " live guard(s) -- "
                + finalFixed + " had the wrong home dimension recorded, now corrected to match their own Guard Kit Stand."), true);
        return fixed;
    }

    /**
     * ADMIN ONLY (LEVEL_GAMEMASTERS, or RCON) -- added 2026-10-29, real report: a Town Hall founded
     * in the Nether had its plots' structures (Plot Config Sign, Construction Box, Guardhouse) spawn
     * right under the world's ceiling, nowhere near the actual founding location -- see {@code
     * util.GroundHeightFinder}'s own doc for the full root-cause writeup. That fix makes every
     * *newly*-placed structure land correctly from here on, but can't retroactively move a structure
     * already stuck up there -- this is the manual, one-time repair for that, same "prefer a one-time
     * fix over permanent self-healing code" reasoning as {@link #fixStuckGuards}.
     *
     * <p>Resolves the target plot the same "stand inside it" way {@link #setPlotOwner}/{@link
     * #resyncShop} already do. Deliberately only ever touches each structure's Y, never its X/Z --
     * the Nether-ceiling bug was purely vertical (a top-down heightmap scan hitting the roof), so
     * there's no reason to recompute where a structure *should* sit horizontally, just drop it down
     * to the real ground directly below (or above, in principle) where it already is, anchored to
     * the executing player's own current Y (they're presumably standing on the real ground right
     * now). Each structure's full state (bound Blueprint/funding progress for the box, NPC worker
     * UUIDs for the sign, food inventory for the Guardhouse) is preserved via a generic block-entity
     * NBT round-trip ({@link #relocateBlockPreservingState}) -- not the existing player-facing
     * "Relocate"/Locator-item flows, which only preserve a sign's bare identity (see {@code
     * plotsign.PendingPlotConfigSignRelocation}'s own doc) and require manual placement, neither of
     * which fits a fully-automated repair command. The Construction Box is Blueprynts' own block,
     * so it goes through a new {@code api.Blueprynts#relocateConstructionBox} (via {@code
     * bridge.BlueprintsConstructionBridge}) instead of this class reaching into Blueprynts directly.
     */
    private static int resetPlotStructures(CommandSourceStack source) {
        if (!(source.getEntity() instanceof net.minecraft.world.entity.player.Player player) || !(source.getLevel() instanceof ServerLevel level)) {
            source.sendFailure(Component.literal("Must be run by a player in-world (stand inside the target plot)."));
            return 0;
        }
        AABB worldBounds = new AABB(-NEAREST_CORE_SEARCH_RADIUS, level.getMinY(), -NEAREST_CORE_SEARCH_RADIUS,
                NEAREST_CORE_SEARCH_RADIUS, level.getMaxY(), NEAREST_CORE_SEARCH_RADIUS);
        BlockPos playerPos = player.blockPosition();
        GhostTownHallCoreEntity foundCore = null;
        PlotRecord foundPlot = null;
        for (GhostTownHallCoreEntity core : level.getEntities(ModEntities.GHOST_TOWN_HALL_CORE.get(), worldBounds, GhostTownHallCoreEntity::isFinalized)) {
            Optional<PlotRecord> plot = com.github.cerealklla.settlemynts.zone.PlotGeometry.findContainingPlot(level, core, playerPos.getX(), playerPos.getZ());
            if (plot.isPresent()) {
                foundCore = core;
                foundPlot = plot.get();
                break;
            }
        }
        if (foundPlot == null) {
            source.sendFailure(Component.literal("You're not standing inside any finalized plot."));
            return 0;
        }
        final GhostTownHallCoreEntity core = foundCore;
        final PlotRecord plot = foundPlot;
        final int referenceY = (int) Math.floor(player.getY());
        MinecraftServer server = level.getServer();
        List<String> moved = new ArrayList<>();

        com.github.cerealklla.settlemynts.plotsign.PlotConfigSignIndex signIndex =
                com.github.cerealklla.settlemynts.plotsign.PlotConfigSignIndex.get(server);
        signIndex.get(plot.plotId())
                .filter(global -> global.dimension().equals(level.dimension()))
                .ifPresent(global -> {
                    BlockPos oldPos = global.pos();
                    int newY = GroundHeightFinder.findNear(level, oldPos.getX(), oldPos.getZ(), referenceY);
                    if (newY != oldPos.getY()) {
                        BlockPos newPos = new BlockPos(oldPos.getX(), newY, oldPos.getZ());
                        if (relocateBlockPreservingState(level, oldPos, newPos)) {
                            signIndex.put(plot.plotId(), GlobalPos.of(level.dimension(), newPos));
                            moved.add("Plot Config Sign");
                        }
                    }
                });

        if (plot.boxPos().isPresent() && ModList.get().isLoaded("blueprynts")) {
            BlockPos oldPos = plot.boxPos().get();
            int newY = GroundHeightFinder.findNear(level, oldPos.getX(), oldPos.getZ(), referenceY);
            if (newY != oldPos.getY()) {
                BlockPos newPos = new BlockPos(oldPos.getX(), newY, oldPos.getZ());
                if (com.github.cerealklla.settlemynts.bridge.BlueprintsConstructionBridge.relocateConstructionBox(level, oldPos, newPos)) {
                    core.updatePlot(plot.withBoxPos(newPos));
                    moved.add("Construction Box");
                }
            }
        }

        com.github.cerealklla.settlemynts.guardhouse.GuardhouseIndex guardhouseIndex =
                com.github.cerealklla.settlemynts.guardhouse.GuardhouseIndex.get(server);
        guardhouseIndex.get(plot.plotId())
                .filter(global -> global.dimension().equals(level.dimension()))
                .ifPresent(global -> {
                    BlockPos oldPos = global.pos();
                    int newY = GroundHeightFinder.findNear(level, oldPos.getX(), oldPos.getZ(), referenceY);
                    if (newY != oldPos.getY()) {
                        BlockPos newPos = new BlockPos(oldPos.getX(), newY, oldPos.getZ());
                        if (relocateBlockPreservingState(level, oldPos, newPos)) {
                            guardhouseIndex.put(plot.plotId(), GlobalPos.of(level.dimension(), newPos));
                            moved.add("Guardhouse");
                        }
                    }
                });

        if (moved.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No structures on this plot needed repositioning."), true);
        } else {
            source.sendSuccess(() -> Component.literal("Repositioned: " + String.join(", ", moved) + "."), true);
        }
        return moved.size();
    }

    /**
     * Moves a block (and its block entity's full custom state) from {@code oldPos} to {@code
     * newPos} via a plain vanilla {@code BlockEntity#saveCustomOnly}/{@code #loadCustomOnly} NBT
     * round-trip -- works generically for any of this mod's own block entities (the Plot Config
     * Sign, the Guardhouse) regardless of their specific fields. See {@code
     * api.Blueprynts#relocateConstructionBox} for the identical, independently-implemented version
     * used for the Construction Box, which this mod can't reach into directly.
     *
     * @return {@code false} if {@code newPos} isn't currently air (never overwrites something else).
     */
    private static boolean relocateBlockPreservingState(ServerLevel level, BlockPos oldPos, BlockPos newPos) {
        if (oldPos.equals(newPos) || !level.getBlockState(newPos).isAir()) {
            return false;
        }
        net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(oldPos);
        net.minecraft.world.level.block.state.BlockState state = level.getBlockState(oldPos);
        net.minecraft.nbt.CompoundTag savedData = be != null ? be.saveCustomOnly(level.registryAccess()) : null;
        level.removeBlock(oldPos, false);
        level.setBlock(newPos, state, 3);
        net.minecraft.world.level.block.entity.BlockEntity newBe = level.getBlockEntity(newPos);
        if (savedData != null && newBe != null) {
            try (net.minecraft.util.ProblemReporter.ScopedCollector reporter =
                         new net.minecraft.util.ProblemReporter.ScopedCollector(newBe.problemPath(), com.github.cerealklla.settlemynts.SettlemyntsMod.LOGGER)) {
                newBe.loadCustomOnly(net.minecraft.world.level.storage.TagValueInput.create(reporter, level.registryAccess(), savedData));
            }
            newBe.setChanged();
        }
        return true;
    }

    private static int plotInfo(CommandSourceStack source) {
        if (!(source.getEntity() instanceof net.minecraft.world.entity.player.Player player) || !(source.getLevel() instanceof ServerLevel level)) {
            source.sendFailure(Component.literal("Must be run by a player in-world."));
            return 0;
        }
        AABB worldBounds = new AABB(-NEAREST_CORE_SEARCH_RADIUS, level.getMinY(), -NEAREST_CORE_SEARCH_RADIUS,
                NEAREST_CORE_SEARCH_RADIUS, level.getMaxY(), NEAREST_CORE_SEARCH_RADIUS);
        List<GhostTownHallCoreEntity> cores = level.getEntities(ModEntities.GHOST_TOWN_HALL_CORE.get(), worldBounds, c -> true);
        GhostTownHallCoreEntity nearest = null;
        double bestDistSq = Double.MAX_VALUE;
        for (GhostTownHallCoreEntity core : cores) {
            double distSq = core.distanceToSqr(player);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                nearest = core;
            }
        }
        if (nearest == null) {
            source.sendFailure(Component.literal("No loaded Town Hall Core found."));
            return 0;
        }

        AABB stakeSearch = new AABB(nearest.getX() - 2000, level.getMinY(), nearest.getZ() - 2000,
                nearest.getX() + 2000, level.getMaxY(), nearest.getZ() + 2000);
        List<GhostPlotStakeEntity> allStakes = level.getEntities(ModEntities.GHOST_PLOT_STAKE.get(), stakeSearch, s -> true);

        GhostTownHallCoreEntity core = nearest;
        source.sendSuccess(() -> Component.literal("Settlement \"" + core.getSettlementName() + "\" -- "
                + allStakes.size() + " live in-progress Plot Fence Post(s) within 2000 blocks, "
                + core.getPlots().size() + " finalized plot(s):"), false);

        for (PlotRecord plot : core.getPlots()) {
            Optional<GeographicEntity> entity = Cartography.getEntity(level, new EntityId(plot.cartographyrPlotEntityId()));
            String vertexInfo;
            if (entity.isPresent() && entity.get().geometry() instanceof Geometry.Polygon polygon) {
                vertexInfo = polygon.vertices().size() + " polygon vertices";
            } else {
                vertexInfo = "no loaded Cartographyr entity";
            }
            String line = "  - \"" + plot.name() + "\": " + vertexInfo;
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    /**
     * {@code /settlemynts guardinfo} -- added 2026-10-30, explicit user request while chasing a real
     * live bug: a guard standing frozen for several real minutes, with no logged failure and nothing
     * obviously wrong in its own saved fields -- the only remaining way to see WHY was to see which
     * {@code Goal} currently actually holds it, which no existing tool exposed. Reports the nearest
     * loaded {@link GuardEntity} to the executing player: its home dimension, settlement/plot
     * identity, current attack target, navigation state, and every currently-running AI goal (see
     * {@link GuardEntity#describeActiveGoals()}).
     */
    private static int guardInfo(CommandSourceStack source) {
        if (!(source.getEntity() instanceof LivingEntity executor) || !(source.getLevel() instanceof ServerLevel level)) {
            source.sendFailure(Component.literal("Must be run by a player in-world."));
            return 0;
        }
        AABB searchBounds = new AABB(executor.getX() - NEAREST_CORE_SEARCH_RADIUS, level.getMinY(), executor.getZ() - NEAREST_CORE_SEARCH_RADIUS,
                executor.getX() + NEAREST_CORE_SEARCH_RADIUS, level.getMaxY(), executor.getZ() + NEAREST_CORE_SEARCH_RADIUS);
        List<GuardEntity> guards = level.getEntities(ModEntities.GUARD.get(), searchBounds, g -> true);
        GuardEntity nearest = null;
        double bestDistSq = Double.MAX_VALUE;
        for (GuardEntity candidate : guards) {
            double distSq = candidate.distanceToSqr(executor);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                nearest = candidate;
            }
        }
        if (nearest == null) {
            source.sendFailure(Component.literal("No loaded Guard found nearby."));
            return 0;
        }

        GuardEntity guard = nearest;
        source.sendSuccess(() -> Component.literal("Guard " + guard.getUUID() + " at " + guard.blockPosition().toShortString()
                + " (" + Math.sqrt(guard.distanceToSqr(executor)) + " blocks away)"), false);
        source.sendSuccess(() -> Component.literal("  HomeDimension: " + guard.homeDimension()
                + " | Current: " + level.dimension().identifier()), false);
        source.sendSuccess(() -> Component.literal("  SettlementCoreId: " + guard.getSettlementCoreId() + " | PlotId: " + guard.plotId()), false);
        source.sendSuccess(() -> Component.literal("  Target: " + guard.getTarget()
                + " | Navigation done: " + guard.getNavigation().isDone()), false);
        List<String> activeGoals = guard.describeActiveGoals();
        source.sendSuccess(() -> Component.literal("  Active goals: " + (activeGoals.isEmpty() ? "(none)" : String.join(", ", activeGoals))), false);
        return 1;
    }

    /**
     * {@code /settlemynts checkavailable <itemId>} -- added 2026-10-22, explicit user request while
     * chasing the real "shop keeps asking for String" bug (see {@code zone.PlannedInventoryClearing}'s
     * own doc for the actual fix). "Available" here means "sellable right now" -- a real Shop listing
     * for this item exists on some plot in the settlement the player is standing in, AND that plot's
     * own boxes currently hold at least 1 unit of it -- a listing with zero stock doesn't count, same
     * distinction {@code ShopWishlist}/{@code PlannedInventoryClearing} already draw everywhere else.
     * Same settlement-resolution (nearest loaded Town Hall Core) as {@link #plotInfo}.
     */
    private static int checkAvailable(CommandSourceStack source, String itemIdRaw) {
        if (!(source.getEntity() instanceof net.minecraft.world.entity.player.Player player) || !(source.getLevel() instanceof ServerLevel level)) {
            source.sendFailure(Component.literal("Must be run by a player in-world."));
            return 0;
        }
        Identifier itemId = Identifier.tryParse(itemIdRaw);
        if (itemId == null || !BuiltInRegistries.ITEM.containsKey(itemId)) {
            source.sendFailure(Component.literal("Not a valid item id: " + itemIdRaw));
            return 0;
        }

        AABB worldBounds = new AABB(-NEAREST_CORE_SEARCH_RADIUS, level.getMinY(), -NEAREST_CORE_SEARCH_RADIUS,
                NEAREST_CORE_SEARCH_RADIUS, level.getMaxY(), NEAREST_CORE_SEARCH_RADIUS);
        List<GhostTownHallCoreEntity> cores = level.getEntities(ModEntities.GHOST_TOWN_HALL_CORE.get(), worldBounds, c -> true);
        GhostTownHallCoreEntity nearest = null;
        double bestDistSq = Double.MAX_VALUE;
        for (GhostTownHallCoreEntity core : cores) {
            double distSq = core.distanceToSqr(player);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                nearest = core;
            }
        }
        if (nearest == null) {
            source.sendFailure(Component.literal("No loaded Town Hall Core found."));
            return 0;
        }

        ShopResource resource = ShopResource.ofItem(itemId);
        PlotRecord sellingPlot = null;
        for (PlotRecord plot : nearest.getPlots()) {
            if (plot.shopId().isEmpty()) {
                continue;
            }
            boolean listed = YconomicsShopBridge.getListings(level, plot.shopId().get()).stream()
                    .anyMatch(listing -> listing.resource().matches(new net.minecraft.world.item.ItemStack(BuiltInRegistries.ITEM.getValue(itemId))));
            if (!listed) {
                continue;
            }
            int stock = ContainerWithdraw.countAvailable(Settlemynts.resolvePlotBoxes(level, plot.plotId()), resource);
            if (stock > 0) {
                sellingPlot = plot;
                break;
            }
        }

        boolean available = sellingPlot != null;
        if (available) {
            PlotRecord finalPlot = sellingPlot;
            source.sendSuccess(() -> Component.literal("true (listed and in stock at \"" + finalPlot.name() + "\")"), false);
        } else {
            source.sendSuccess(() -> Component.literal("false"), false);
        }
        return available ? 1 : 0;
    }

    /**
     * {@code /settlemynts watchtowerinfo} -- added 2026-10-22, real report: "it just says Unlit no
     * matter what" even with plenty of fuel and the bonus toggled on, close/reopen confirmed not a
     * staleness issue. Dumps the nearest Beacon's own real internal state (within {@code SEARCH_RADIUS}
     * blocks of the player) instead of continuing to theorize from code alone.
     */
    private static final int WATCH_TOWER_SEARCH_RADIUS = 12;

    private static int watchTowerInfo(CommandSourceStack source) {
        if (!(source.getEntity() instanceof net.minecraft.world.entity.player.Player player) || !(source.getLevel() instanceof ServerLevel level)) {
            source.sendFailure(Component.literal("Must be run by a player in-world."));
            return 0;
        }
        BlockPos center = player.blockPosition();
        com.github.cerealklla.settlemynts.watchtower.WatchTowerBeaconBlockEntity found = null;
        BlockPos foundPos = null;
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-WATCH_TOWER_SEARCH_RADIUS, -WATCH_TOWER_SEARCH_RADIUS, -WATCH_TOWER_SEARCH_RADIUS),
                center.offset(WATCH_TOWER_SEARCH_RADIUS, WATCH_TOWER_SEARCH_RADIUS, WATCH_TOWER_SEARCH_RADIUS))) {
            if (level.getBlockEntity(pos) instanceof com.github.cerealklla.settlemynts.watchtower.WatchTowerBeaconBlockEntity beacon) {
                found = beacon;
                foundPos = pos.immutable();
                break;
            }
        }
        if (found == null) {
            source.sendFailure(Component.literal("No Watch Tower Beacon found within " + WATCH_TOWER_SEARCH_RADIUS + " blocks."));
            return 0;
        }
        BlockPos finalFoundPos = foundPos;
        com.github.cerealklla.settlemynts.watchtower.WatchTowerBeaconBlockEntity finalFound = found;
        source.sendSuccess(() -> Component.literal("Beacon at " + finalFoundPos.toShortString() + ": " + finalFound.debugDump(level)), false);
        return 1;
    }
}
