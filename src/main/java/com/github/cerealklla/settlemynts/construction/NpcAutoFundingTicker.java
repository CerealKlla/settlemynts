package com.github.cerealklla.settlemynts.construction;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.github.cerealklla.blueprynts.api.Blueprynts;
import com.github.cerealklla.blueprynts.blueprint.GenericResource;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.registration.ModEntities;
import com.github.cerealklla.settlemynts.zone.PlotRecord;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.minecraft.world.phys.AABB;

/**
 * NPC auto-funding for Construction Boxes (design-document.md Section 14a, captured 2026-09-29,
 * built 2026-09-30) -- for any finalized plot with no Owner set (NPC-owned, the default), this
 * periodically deposits whatever resources are still missing from its Building Supply Box, at a
 * pace sized so that, left entirely unfunded by players, the box reaches 100% funded right around
 * its own {@code maxTimeTicks} (the Tier's maximum construction time, already flowing through
 * {@code ZoneTierConstructionConfig} -> {@code FundingRequirements} -> the box). A player depositing
 * concurrently shrinks the box's own {@code missing} amount, which naturally caps/ends NPC delivery
 * early -- no separate "player contributed" tracking needed. The box's own {@code minTimeTicks}
 * floor ({@code attemptCompletion}'s {@code min(fundedPercent, timeBasedPercent)}) already stops
 * construction from finishing early regardless of funding source, so nothing here needs to
 * re-enforce it.
 *
 * <p><b>Rate model, reworked 2026-09-30</b> -- two directly-named, independently config-derived
 * quantities (explicit user request, replacing an earlier single combined-formula version): {@code
 * ResourceDropOffTickInterval = maxTimeTicks * DROPOFF_INTERVAL_FRACTION} (how often a box
 * delivers) and {@code ResourcesDroppedPerDelivery = required * DROPOFF_AMOUNT_FRACTION} (how much
 * per delivery, per resource). Both fractions are currently 1%, so left unfunded a box still
 * reaches 100% around {@code maxTimeTicks} (~100 deliveries of ~1% each) -- but retuning {@code
 * maxTimeTicks} (Type+Tier config) or a Blueprint's resource costs now automatically reflows
 * through both numbers with no code change, which a single combined ceil-division formula didn't
 * make obvious.
 *
 * <p>Since {@code ResourceDropOffTickInterval} is now per-box (derived from that box's own {@code
 * maxTimeTicks}), not one global constant, a single "gate everything by one fixed interval" tick
 * handler no longer works -- different boxes need different cadences. Instead, a small fixed {@link
 * #SCAN_INTERVAL_TICKS} gates how often the (expensive) eligibility scan itself runs, and each
 * eligible box is independently checked for whether it's *due* a delivery via a windowed modulo
 * test (see {@link #isDeliveryWindow}) -- fires exactly once per that box's own drop-off interval,
 * with no new persisted per-box "last delivered" state needed.
 *
 * <p><b>Known scaling simplification</b>: finding every loaded finalized settlement core uses a
 * single large-radius {@code level.getEntities(...)} query (a full-world bounding box) rather than a
 * maintained registry, the same "simple now, optimize later" shape {@code
 * founding.PlannedPerimeterStakeItem}'s own (smaller-radius) core lookup already uses. Only
 * currently-loaded plots/boxes are reachable this way -- the same limitation the box's own {@code
 * minTimeTicks} pacing already has (it only advances while ticking).
 */
public class NpcAutoFundingTicker {

    // How often the (expensive) eligibility scan runs -- cheap enough at 1 second that it can
    // reliably catch any box's own drop-off window (see isDeliveryWindow) without needing per-box
    // "last delivered" state.
    private static final int SCAN_INTERVAL_TICKS = 20;

    // ResourceDropOffTickInterval / ResourcesDroppedPerDelivery, both 1% -- see class doc.
    private static final double DROPOFF_INTERVAL_FRACTION = 0.01;
    private static final double DROPOFF_AMOUNT_FRACTION = 0.01;

    private static final double WORLD_SCAN_RADIUS = 3.0E7;

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (!ModList.get().isLoaded("blueprynts")) {
            return;
        }
        MinecraftServer server = event.getServer();
        int tickCount = server.getTickCount();
        if (tickCount % SCAN_INTERVAL_TICKS != 0) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            AABB worldBounds = new AABB(-WORLD_SCAN_RADIUS, level.getMinY(), -WORLD_SCAN_RADIUS,
                    WORLD_SCAN_RADIUS, level.getMaxY(), WORLD_SCAN_RADIUS);
            List<GhostTownHallCoreEntity> cores = level.getEntities(ModEntities.GHOST_TOWN_HALL_CORE.get(), worldBounds,
                    GhostTownHallCoreEntity::isFinalized);
            for (GhostTownHallCoreEntity core : cores) {
                for (PlotRecord plot : core.getPlots()) {
                    if (plot.owner().isPresent() || plot.constructionBoxId().isEmpty()) {
                        continue;
                    }
                    deliverTo(level, plot.constructionBoxId().get(), tickCount);
                }
            }
        }
    }

    /** True on the first scan tick at or after a multiple of {@code dropOffIntervalTicks} -- fires exactly once per window as long as the window is at least {@link #SCAN_INTERVAL_TICKS} wide, true for any realistic {@code maxTimeTicks}. */
    private static boolean isDeliveryWindow(int tickCount, int dropOffIntervalTicks) {
        return tickCount % dropOffIntervalTicks < SCAN_INTERVAL_TICKS;
    }

    private void deliverTo(ServerLevel level, UUID constructionBoxId, int tickCount) {
        BlockPos boxPos = Blueprynts.getConstructionBoxPos(level, constructionBoxId).orElse(null);
        if (boxPos == null) {
            return;
        }
        Blueprynts.NpcFundingState state = Blueprynts.getNpcFundingState(level, boxPos).orElse(null);
        if (state == null || state.maxTimeTicks() <= 0) {
            return;
        }
        int dropOffIntervalTicks = Math.max(1, (int) Math.round(state.maxTimeTicks() * DROPOFF_INTERVAL_FRACTION));
        if (!isDeliveryWindow(tickCount, dropOffIntervalTicks)) {
            return;
        }
        for (Map.Entry<GenericResource, Blueprynts.ResourceFunding> entry : state.resources().entrySet()) {
            Blueprynts.ResourceFunding funding = entry.getValue();
            int required = funding.required();
            int missing = funding.missing();
            if (required <= 0 || missing <= 0) {
                continue;
            }
            int perDelivery = Math.max(1, (int) Math.ceil(required * DROPOFF_AMOUNT_FRACTION));
            int amount = Math.min(perDelivery, missing);
            Blueprynts.depositResource(level, boxPos, entry.getKey(), amount);
        }
    }
}
