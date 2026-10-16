package com.github.cerealklla.settlemynts.zone;

import java.util.List;
import java.util.UUID;

import com.github.cerealklla.settlemynts.api.Settlemynts;
import com.github.cerealklla.settlemynts.farm.FarmerWorkerEntity;
import com.github.cerealklla.settlemynts.lumberyard.LumberjackWorkerEntity;
import com.github.cerealklla.settlemynts.mineshaft.MinerWorkerEntity;
import com.github.cerealklla.settlemynts.plotsign.PlotConfigSignBlockEntity;
import com.github.cerealklla.settlemynts.resident.ResidentVillagerEntity;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Cost math and fund-draining for a player-owned plot's hired NPC -- design doc Section 14a,
 * explicit user request, 2026-10-15: "cost the plot 10 * Tier to 'maintain' every midnight." Shared
 * by both the manual "Hire NPC" button ({@code SettlemyntsMod#requestHireNpc}) and {@link
 * NpcMaintenanceTicker}'s own automatic midnight charge, so a manual re-hire after a funds lapse
 * charges the exact same amount the midnight tick would have.
 *
 * <p>There's no per-plot stored currency balance anywhere in this mod -- "funds" means real {@link
 * Items#GOLD_NUGGET} stacks physically sitting in the plot's own boxes ({@link
 * Settlemynts#resolvePlotBoxes}), the same convention {@code construction.PlotTierUpgradeFunding}
 * already uses for its own resource costs. {@link #tryCharge} is a plain scan-then-drain over that
 * same item, not tied to any {@code GenericResource}/Blueprynts concept (gold nuggets need none of
 * that machinery).
 */
public final class NpcHireMaintenance {

    private static final int GOLD_PER_TIER = 10;
    private static final long TICKS_PER_DAY = 24000L;

    private NpcHireMaintenance() {
    }

    /** {@code 10 * Tier} -- the user's own literal formula. {@code Math.max(1, ...)} is just defensive; {@code PlotRecord#tier} is never really 0. */
    public static int costFor(PlotRecord plot) {
        return GOLD_PER_TIER * Math.max(1, plot.tier());
    }

    /** The real-day index a charge made right now would be attributed to -- shared so a manual "Hire NPC" payment and the midnight ticker's own day tracking never disagree about what day it is. */
    public static long currentDay(MinecraftServer server) {
        return Math.floorDiv(server.overworld().getOverworldClockTime(), TICKS_PER_DAY);
    }

    /** Attempts to drain {@code cost} Gold Nuggets from the plot's own boxes -- all-or-nothing, never a partial drain on failure. */
    public static boolean tryCharge(ServerLevel level, UUID plotId, int cost) {
        List<Container> boxes = Settlemynts.resolvePlotBoxes(level, plotId);
        if (countAvailable(boxes) < cost) {
            return false;
        }
        int remaining = cost;
        for (Container box : boxes) {
            for (int slot = 0; slot < box.getContainerSize() && remaining > 0; slot++) {
                ItemStack stack = box.getItem(slot);
                if (stack.getItem() != Items.GOLD_NUGGET) {
                    continue;
                }
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                remaining -= take;
            }
        }
        return true;
    }

    private static int countAvailable(List<Container> boxes) {
        int total = 0;
        for (Container box : boxes) {
            for (int slot = 0; slot < box.getContainerSize(); slot++) {
                ItemStack stack = box.getItem(slot);
                if (stack.getItem() == Items.GOLD_NUGGET) {
                    total += stack.getCount();
                }
            }
        }
        return total;
    }

    /**
     * Removes whatever worker is currently filling {@code sign}'s slot, directly -- used when a
     * maintenance charge fails and the plot's hired NPC must go away until the next successful
     * payment. Deliberately bypasses {@link PlotNpcDeathListener} (a real combat death, which this
     * isn't -- {@link net.minecraft.world.entity.Entity#discard()} never fires {@code
     * LivingDeathEvent}) and clears the matching slot directly here instead, the same per-type
     * dispatch that listener itself uses so the two can never disagree about which slot a given
     * entity type owns.
     *
     * <p>{@code npcHired} on the plot record is deliberately left untouched by this method -- the
     * caller keeps it {@code true} so a later midnight automatically retries and restores the worker
     * once the plot is funded again, per the user's own spec ("despawn... until enough is available
     * the following midnight").
     */
    public static void despawnHiredWorker(ServerLevel level, PlotConfigSignBlockEntity sign) {
        if (sign.residentVillagerId() != null) {
            if (level.getEntity(sign.residentVillagerId()) instanceof ResidentVillagerEntity entity) {
                entity.discard();
            }
            sign.setResidentVillagerId(null);
        }
        if (sign.farmerWorkerId() != null) {
            if (level.getEntity(sign.farmerWorkerId()) instanceof FarmerWorkerEntity entity) {
                entity.discard();
            }
            sign.setFarmerWorkerId(null);
        }
        if (sign.lumberjackWorkerId() != null) {
            if (level.getEntity(sign.lumberjackWorkerId()) instanceof LumberjackWorkerEntity entity) {
                entity.discard();
            }
            sign.setLumberjackWorkerId(null);
        }
        if (sign.minerWorkerId() != null) {
            if (level.getEntity(sign.minerWorkerId()) instanceof MinerWorkerEntity entity) {
                entity.discard();
            }
            sign.setMinerWorkerId(null);
        }
    }
}
