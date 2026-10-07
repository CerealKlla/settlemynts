package com.github.cerealklla.settlemynts.zone;

import java.util.ArrayList;
import java.util.List;

import com.github.cerealklla.cartographyr.geo.Geometry;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.registration.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * The "nearest roadway" flag item (design doc Section 10) -- see {@link GhostRoadAccessFlagEntity}'s
 * own doc. Reuses {@link PlotSessionData} exactly like {@link PlotPlacementStakeItem} does since its
 * own rework (2026-09-30) -- granted bound to whichever plot's post it was requested from ({@code
 * client.PlotStakeScreen}'s "Get Road Access Flag" button), but its {@link
 * PlotSessionData#plotSessionId()} ("CurrentPlotID") is mutable from there on, same lifecycle as
 * Plot Stakes: real bug report, 2026-09-30 -- "The Plot specific road item isn't removing from my
 * hands after finalizing a plot, but also can't be reused," because it used to be permanently bound
 * at grant. Now: right-clicking any Plot Fence Post while holding it (see {@code
 * GhostPlotStakeEntity#interact}) rebinds it to that post's plot; switching away from it in hand
 * resets CurrentPlotID back to blank ({@code SettlemyntsMod#onLeashTick}); Finalize resets it too if
 * it was still bound to the plot that just finished ({@code SettlemyntsMod#clearPlotStakeItems});
 * dropping it deletes it outright ({@code zone.PlotStakeTossGuard}); and carrying it outside its
 * granting settlement removes it from the inventory ({@code
 * SettlemyntsMod#removeStalePlotItemsOutsideSettlement}).
 */
public class RoadAccessFlagItem extends Item {

    public RoadAccessFlagItem(Properties properties) {
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

        PlotSessionData session = context.getItemInHand().get(ModItems.PLOT_SESSION_DATA);
        // Road Access Flag rework (2026-09-30, see this class's own doc) -- a blank CurrentPlotID is
        // now a routine state (right after a hand-swap, a Finalize, or leaving/re-entering the
        // settlement), not just a theoretical edge case, so this is the normal "not bound yet" path,
        // not a defensive-only guard.
        if (session == null || session.plotSessionId() == null) {
            player.sendSystemMessage(Component.literal("This flag isn't bound to a plot -- right-click one of that plot's Fence Posts first."));
            return InteractionResult.FAIL;
        }
        if (!(serverLevel.getEntity(session.ownerCoreId()) instanceof GhostTownHallCoreEntity core) || !core.isTownPlanner(player.getUUID())) {
            player.sendSystemMessage(Component.literal("This flag's settlement is gone, or you're no longer a Town Planner there."));
            return InteractionResult.FAIL;
        }

        List<GhostPlotStakeEntity> stakes = GhostPlotStakeEntity.findBySessionInPlacementOrder(serverLevel, session.ownerCoreId(), session.plotSessionId());
        if (stakes.size() < 3) {
            player.sendSystemMessage(Component.literal("Place at least 3 plot stakes before placing the Road Access Flag."));
            return InteractionResult.FAIL;
        }
        List<PlotGeometry.StakePoint> ordered = new ArrayList<>(stakes.size());
        for (GhostPlotStakeEntity stake : stakes) {
            ordered.add(new PlotGeometry.StakePoint(stake.getX(), stake.getZ()));
        }
        Geometry.Polygon plotPolygon = PlotGeometry.polygonFromStakes(ordered);

        BlockPos placePos = context.getClickedPos().above();
        if (!PlotGeometry.isOnPerimeter(plotPolygon, placePos.getX(), placePos.getZ())) {
            player.sendSystemMessage(Component.literal("The Road Access Flag can only be placed on the plot's own perimeter."));
            return InteractionResult.FAIL;
        }

        // Only one allowed per session -- placing a new one replaces the old (design doc Section 10:
        // "a special 'nearest roadway' flag," singular), same undo-style-replace shape used elsewhere
        // in this mod rather than rejecting a second placement outright.
        GhostRoadAccessFlagEntity existing = GhostRoadAccessFlagEntity.findBySession(serverLevel, session.ownerCoreId(), session.plotSessionId());
        if (existing != null) {
            existing.remove(player);
        }
        GhostRoadAccessFlagEntity.create(serverLevel, placePos.getX(), placePos.getY() + 1, placePos.getZ(),
                session.ownerCoreId(), session.plotSessionId());
        // The preview stays visible -- the item is reusable (see this class's own doc), and
        // SettlemyntsMod#onServerTick no longer hides it just because a flag already exists, so no
        // explicit clear is needed here; the next tick just keeps showing valid placement spots.
        return InteractionResult.SUCCESS_SERVER;
    }
}
