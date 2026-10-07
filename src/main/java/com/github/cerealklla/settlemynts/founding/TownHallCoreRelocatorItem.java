package com.github.cerealklla.settlemynts.founding;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import com.github.cerealklla.cartographyr.api.Cartography;
import com.github.cerealklla.cartographyr.geo.EntityId;
import com.github.cerealklla.cartographyr.geo.GeographicEntity;
import com.github.cerealklla.cartographyr.geo.Geometry;
import com.github.cerealklla.settlemynts.registration.ModEntities;
import com.github.cerealklla.settlemynts.registration.ModItems;
import com.github.cerealklla.settlemynts.zone.PlotRecord;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * "Reposition Town Hall Core" hand-off item (added 2026-09-30, explicit user request: "We should be
 * able to reposition the Town Hall Core, if it's within a Town Hall Plot, to anywhere within the
 * plot in the same way we can move the Plot Sign around"). Mirrors "Relocate Plot Sign"'s UX (a
 * granted Locator item, bounded placement, walk-away/death/logout auto-cancel) but is a plain {@link
 * Item}, not a {@link net.minecraft.world.item.BlockItem} -- {@link GhostTownHallCoreEntity} is a
 * pure marker {@code Entity} with no block to place, so this teleports it directly on {@link
 * #useOn}.
 *
 * <p><b>Simpler than the sign/box relocation flows</b>: those remove the real block immediately and
 * must stash enough state to restore it if the player never finishes or walks away, since "gone
 * until replaced" is a bad interim state for a real block. The Core never moves until the *moment* a
 * valid new position is chosen (one atomic {@code setPos} call) -- there's nothing to restore on
 * cancel, so no {@code Pending*Relocation} map exists for this feature at all; cancellation just
 * clears the stray item, the same shape Blueprynts' {@code BuildingLocatorItem#cancelIfHeld} already
 * uses for exactly this same reason.
 */
public class TownHallCoreRelocatorItem extends Item {

    private static final long GRACE_TICKS = 60; // 3 seconds -- same reasoning as PlotConfigSignRelocatorItem's own.
    private static final double WORLD_SCAN_RADIUS = 3.0E7;

    public TownHallCoreRelocatorItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.SUCCESS;
        }
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.FAIL;
        }
        ItemStack stack = context.getItemInHand();
        UUID coreUuid = stack.get(ModItems.TOWN_HALL_CORE_LOCATOR_CORE_ID);
        if (coreUuid == null) {
            player.sendSystemMessage(Component.literal("This Town Hall Core Locator isn't bound to a settlement."));
            return InteractionResult.FAIL;
        }
        GhostTownHallCoreEntity core = findCore(serverLevel, coreUuid);
        if (core == null) {
            player.sendSystemMessage(Component.literal("This Town Hall Core Locator's settlement is gone or not loaded."));
            return InteractionResult.FAIL;
        }
        Optional<PlotRecord> townHallPlot = core.findTownHallPlot(serverLevel);
        if (townHallPlot.isEmpty()) {
            player.sendSystemMessage(Component.literal("The Town Hall Core isn't currently inside a finalized Town Hall plot."));
            return InteractionResult.FAIL;
        }
        Geometry.Polygon polygon = resolvePolygon(serverLevel, townHallPlot.get()).orElse(null);
        BlockPos target = context.getClickedPos().above();
        if (polygon == null || !polygon.contains(target.getX(), target.getZ())) {
            player.sendSystemMessage(Component.literal("That's outside the Town Hall Plot -- the Core must stay within it."));
            return InteractionResult.FAIL;
        }

        core.discardBellMarker(serverLevel);
        core.setPos(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
        core.spawnBellMarker(serverLevel);
        player.sendSystemMessage(Component.literal("Town Hall Core repositioned."));
        stack.shrink(1);
        return InteractionResult.SUCCESS_SERVER;
    }

    private static Optional<Geometry.Polygon> resolvePolygon(ServerLevel level, PlotRecord plot) {
        Optional<GeographicEntity> entity = Cartography.getEntity(level, new EntityId(plot.cartographyrPlotEntityId()));
        return entity.filter(e -> e.geometry() instanceof Geometry.Polygon).map(e -> (Geometry.Polygon) e.geometry());
    }

    /** No chunk-independent index exists for this entity type (it's never removed, only moved) -- same large-AABB scan {@code construction.NpcAutoFundingTicker} already uses for the same reason. */
    private static GhostTownHallCoreEntity findCore(ServerLevel level, UUID coreUuid) {
        AABB worldBounds = new AABB(-WORLD_SCAN_RADIUS, level.getMinY(), -WORLD_SCAN_RADIUS,
                WORLD_SCAN_RADIUS, level.getMaxY(), WORLD_SCAN_RADIUS);
        List<GhostTownHallCoreEntity> found = level.getEntities(ModEntities.GHOST_TOWN_HALL_CORE.get(), worldBounds,
                c -> c.getUUID().equals(coreUuid));
        return found.isEmpty() ? null : found.get(0);
    }

    /** A fresh, bound Town Hall Core Locator stack for {@code coreUuid}. */
    public static ItemStack grantFor(UUID coreUuid, long gameTime) {
        ItemStack stack = new ItemStack(ModItems.TOWN_HALL_CORE_RELOCATOR.get());
        stack.set(ModItems.TOWN_HALL_CORE_LOCATOR_CORE_ID, coreUuid);
        stack.set(ModItems.TOWN_HALL_CORE_LOCATOR_GRANTED_AT, gameTime);
        return stack;
    }

    /**
     * Auto-cancels a held, bound Town Hall Core Locator once the player leaves the Town Hall Plot (or
     * unconditionally on death/logout) -- same shape as the other two Locators' own {@code
     * cancelIfHeld}. Nothing is ever restored (see class doc) -- this only ever clears the stray item.
     *
     * @param force skips the bounds check -- used by death/logout cancellation.
     */
    public static void cancelIfHeld(ServerLevel level, Player player, ItemStack stack, boolean force, Consumer<ItemStack> clearStack) {
        UUID coreUuid = stack.get(ModItems.TOWN_HALL_CORE_LOCATOR_CORE_ID);
        if (coreUuid == null) {
            return;
        }
        if (!force) {
            Long grantedAt = stack.get(ModItems.TOWN_HALL_CORE_LOCATOR_GRANTED_AT);
            if (grantedAt != null && level.getGameTime() - grantedAt < GRACE_TICKS) {
                return;
            }
        }
        GhostTownHallCoreEntity core = findCore(level, coreUuid);
        if (core == null) {
            return;
        }
        Optional<PlotRecord> townHallPlot = core.findTownHallPlot(level);
        if (townHallPlot.isEmpty()) {
            return; // No known bounds to enforce -- nothing to cancel yet.
        }
        if (!force) {
            Geometry.Polygon polygon = resolvePolygon(level, townHallPlot.get()).orElse(null);
            BlockPos pos = player.blockPosition();
            if (polygon == null || polygon.contains(pos.getX(), pos.getZ())) {
                return; // Unbound, or still inside the plot -- nothing to cancel yet.
            }
        }
        clearStack.accept(stack);
        player.sendSystemMessage(Component.literal("Reposition canceled -- you left the Town Hall Plot."));
    }
}
