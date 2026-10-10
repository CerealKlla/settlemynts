package com.github.cerealklla.settlemynts.zone;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.github.cerealklla.settlemynts.SettlemyntsMod;
import com.github.cerealklla.settlemynts.api.Settlemynts;
import com.github.cerealklla.settlemynts.bridge.LyfeCraftingBridge;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.registration.ModEntities;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * NPC plot crafting, continuous production half (2026-10-09, explicit user request: "When a plot has
 * all the materials to craft something and it is under the Planned Inventory minimum of that item,
 * and it has a structure on the plot that can craft that item, it'll immediately consume those
 * materials to craft the item"). Same scan shape as {@code construction.NpcAutoFundingTicker} (the
 * closest existing "production ticker" precedent): a fixed {@link #SCAN_INTERVAL_TICKS} gates a
 * full-world finalized-core scan, no maintained registry -- same accepted "simple now, optimize later"
 * tradeoff that ticker already makes. Only player-founded settlements are scanned (natural villages
 * never spawn a Lyfe crafting structure, same scope {@code NpcAutoFundingTicker} already has).
 *
 * <p>{@link #MAX_CRAFTS_PER_PLOT_PER_TARGET} is a safety bound against a pathological loop, not a
 * deliberate pacing mechanic -- unlike {@code NpcAutoFundingTicker}'s own deliberate slow drip, this
 * genuinely tries to craft as much as it can, as soon as it can, matching "immediately" in the spec.
 *
 * <p>The midnight half of this feature (deciding whether to buy a finished item outright or buy its
 * materials and craft it) lives in {@link PlannedInventoryClearing}, which calls {@link
 * CraftingExecutor} directly rather than through this ticker.
 */
public final class PlotCraftingTicker {

    private static final int SCAN_INTERVAL_TICKS = 20;
    private static final double WORLD_SCAN_RADIUS = 3.0E7;
    private static final int MAX_CRAFTS_PER_PLOT_PER_TARGET = 64;

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (!LyfeCraftingBridge.isLoaded()) {
            return;
        }
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % SCAN_INTERVAL_TICKS != 0) {
            return;
        }
        // Temporary debug logging (2026-10-10, real report: "the server seems very laggy right now")
        // -- every scan pass is logged with its own timing so a lag spike can be correlated against
        // this ticker specifically rather than guessed at.
        long passStart = System.nanoTime();
        int coresScanned = 0;
        int plotsChecked = 0;
        int totalCrafted = 0;
        for (ServerLevel level : server.getAllLevels()) {
            AABB worldBounds = new AABB(-WORLD_SCAN_RADIUS, level.getMinY(), -WORLD_SCAN_RADIUS,
                    WORLD_SCAN_RADIUS, level.getMaxY(), WORLD_SCAN_RADIUS);
            for (GhostTownHallCoreEntity core : level.getEntities(ModEntities.GHOST_TOWN_HALL_CORE.get(), worldBounds,
                    GhostTownHallCoreEntity::isFinalized)) {
                coresScanned++;
                for (PlotRecord plot : core.getPlots()) {
                    if (plot.shopId().isPresent() && !plot.plannedInventory().isEmpty()) {
                        plotsChecked++;
                        totalCrafted += craftForPlot(level, plot);
                    }
                }
            }
        }
        double passMs = (System.nanoTime() - passStart) / 1_000_000.0;
        if (totalCrafted > 0 || passMs > 20.0) {
            SettlemyntsMod.LOGGER.info(
                    "PlotCraftingTicker: scanned {} core(s)/{} eligible plot(s) in {}ms, crafted {} item(s) total",
                    coresScanned, plotsChecked, String.format("%.1f", passMs), totalCrafted);
        }
    }

    /** @return how many individual crafts this plot actually performed this pass. */
    private int craftForPlot(ServerLevel level, PlotRecord plot) {
        long plotStart = System.nanoTime();
        List<Container> boxes = null;
        int structureTier = -1; // Lazily resolved once per plot -- -1 means "not checked yet."
        int craftedThisPlot = 0;
        for (PlannedInventoryTarget target : plot.plannedInventory()) {
            if (target.isTag()) {
                continue; // A crafting recipe's output is always a concrete item.
            }
            Optional<LyfeCraftingBridge.RecipeInfo> recipeOpt = LyfeCraftingBridge.getRecipe(target.resourceKey());
            if (recipeOpt.isEmpty()) {
                continue;
            }
            if (structureTier < 0) {
                structureTier = PlotCraftingStructures.maxCraftingStructureTier(level, plot.plotId());
            }
            LyfeCraftingBridge.RecipeInfo recipe = recipeOpt.get();
            if (structureTier < recipe.tier()) {
                continue; // No sufficiently-tiered crafting structure present on this plot.
            }
            if (boxes == null) {
                boxes = Settlemynts.resolvePlotBoxes(level, plot.plotId());
                if (boxes.isEmpty()) {
                    return craftedThisPlot;
                }
            }
            for (int crafted = 0; crafted < MAX_CRAFTS_PER_PLOT_PER_TARGET; crafted++) {
                Map<Identifier, Integer> available = Settlemynts.scanPlotItemStock(level, plot.plotId());
                int currentStock = available.getOrDefault(target.resourceKey(), 0);
                if (currentStock >= target.targetCount() || !CraftingExecutor.canCraftOne(available, recipe)) {
                    break;
                }
                CraftingExecutor.craftOne(boxes, recipe);
                craftedThisPlot++;
            }
        }
        if (craftedThisPlot > 0) {
            double plotMs = (System.nanoTime() - plotStart) / 1_000_000.0;
            SettlemyntsMod.LOGGER.info(
                    "PlotCraftingTicker: plot {} crafted {} item(s) in {}ms (structure tier {})",
                    plot.plotId(), craftedThisPlot, String.format("%.1f", plotMs), structureTier);
        }
        return craftedThisPlot;
    }
}
