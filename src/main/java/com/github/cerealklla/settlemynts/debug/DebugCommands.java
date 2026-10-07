package com.github.cerealklla.settlemynts.debug;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import com.github.cerealklla.cartographyr.api.Cartography;
import com.github.cerealklla.cartographyr.geo.EntityId;
import com.github.cerealklla.cartographyr.geo.GeographicEntity;
import com.github.cerealklla.cartographyr.geo.Geometry;
import com.github.cerealklla.settlemynts.bridge.YconomicsBillBridge;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.registration.ModEntities;
import com.github.cerealklla.settlemynts.zone.GhostPlotStakeEntity;
import com.github.cerealklla.settlemynts.zone.PlotRecord;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
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
                .then(Commands.literal("plot")
                        .then(Commands.literal("bill").then(billPlotId))));
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
}
