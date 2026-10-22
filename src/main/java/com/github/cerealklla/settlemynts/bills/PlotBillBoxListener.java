package com.github.cerealklla.settlemynts.bills;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.github.cerealklla.cartographyr.box.BoxAttachments;
import com.github.cerealklla.settlemynts.bridge.YconomicsBillBridge;
import com.github.cerealklla.settlemynts.bridge.YconomicsBillBridge.BoxRef;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.registration.ModEntities;
import com.github.cerealklla.settlemynts.zone.PlotGeometry;
import com.github.cerealklla.settlemynts.zone.PlotRecord;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;

/**
 * Keeps every player-owned plot's recurring rent bill pointed at real boxes incrementally, as
 * boxes are placed/broken -- replaces {@code PlotRentTicker}'s periodic full-world rescan entirely
 * (deleted 2026-10-22, see decisions.md). Per the user's own original spec ("when a chest is
 * placed, give it a UUID and register it with Yconomics... Chest Place = Event, Chest Destroyed =
 * Event"), this reads the box's id directly off {@link BoxAttachments#BOX_ID} -- the same
 * attachment {@code Cartographyr.box.BoxPlacementListener} itself mints it from, independently of
 * that listener's own execution order ({@link BreakBlockEvent} "fires pre-removal -- the block
 * entity (and its attachment) is still readable here," same guarantee that listener's own doc
 * documents).
 *
 * <p>Still does a world scan to resolve "which plot (if any) is this box in," same shape {@code
 * api.Settlemynts#findPlotAt} uses -- but only once per real box placement/break, not once every
 * 20 ticks regardless of whether anything actually changed, which is what made the old ticker a
 * real, measured lag source (see decisions.md's JFR writeup).
 */
public final class PlotBillBoxListener {

    private static final double WORLD_SCAN_RADIUS = 3.0E7;

    @SubscribeEvent
    public void onEntityPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel) || !YconomicsBillBridge.isAvailable()) {
            return;
        }
        if (!(serverLevel.getBlockEntity(event.getPos()) instanceof BlockEntity blockEntity)) {
            return;
        }
        blockEntity.getExistingData(BoxAttachments.BOX_ID)
                .ifPresent(boxId -> onBoxChanged(serverLevel, event.getPos(), boxId, true));
    }

    @SubscribeEvent
    public void onBreakBlock(BreakBlockEvent event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel) || !YconomicsBillBridge.isAvailable()) {
            return;
        }
        // Fires pre-removal -- the block entity (and its attachment) is still readable here.
        if (!(serverLevel.getBlockEntity(event.getPos()) instanceof BlockEntity blockEntity)) {
            return;
        }
        blockEntity.getExistingData(BoxAttachments.BOX_ID)
                .ifPresent(boxId -> onBoxChanged(serverLevel, event.getPos(), boxId, false));
    }

    private void onBoxChanged(ServerLevel level, BlockPos pos, UUID boxId, boolean added) {
        AABB worldBounds = new AABB(-WORLD_SCAN_RADIUS, level.getMinY(), -WORLD_SCAN_RADIUS,
                WORLD_SCAN_RADIUS, level.getMaxY(), WORLD_SCAN_RADIUS);
        for (GhostTownHallCoreEntity core : level.getEntities(ModEntities.GHOST_TOWN_HALL_CORE.get(), worldBounds,
                GhostTownHallCoreEntity::isFinalized)) {
            Optional<PlotRecord> containing = PlotGeometry.findContainingPlot(level, core, pos.getX(), pos.getZ());
            if (containing.isEmpty()) {
                continue;
            }
            PlotRecord plot = containing.get();
            BoxRef ref = new BoxRef(boxId, GlobalPos.of(level.dimension(), pos));
            if (GhostTownHallCoreEntity.TOWN_HALL_ZONE_TYPE_ID.equals(plot.zoneTypeId())) {
                for (PlotRecord other : core.getPlots()) {
                    if (other.plotId().equals(plot.plotId()) || other.billId().isEmpty()) {
                        continue;
                    }
                    applyChange(level, other.billId().get(), ref, added, false);
                }
            } else if (plot.billId().isPresent()) {
                applyChange(level, plot.billId().get(), ref, added, true);
            }
            return;
        }
    }

    private void applyChange(ServerLevel level, UUID billId, BoxRef ref, boolean added, boolean isSource) {
        Optional<YconomicsBillBridge.BillBoxes> current = YconomicsBillBridge.getBillBoxes(level, billId);
        if (current.isEmpty()) {
            return;
        }
        List<BoxRef> sourceBoxes = new ArrayList<>(current.get().sourceBoxes());
        List<BoxRef> destinationBoxes = new ArrayList<>(current.get().destinationBoxes());
        List<BoxRef> target = isSource ? sourceBoxes : destinationBoxes;
        target.removeIf(existing -> existing.id().equals(ref.id()));
        if (added) {
            target.add(ref);
        }
        YconomicsBillBridge.updateBillBoxes(level, billId, sourceBoxes, destinationBoxes);
    }
}
