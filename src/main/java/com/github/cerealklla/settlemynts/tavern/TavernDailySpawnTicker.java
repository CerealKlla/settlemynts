package com.github.cerealklla.settlemynts.tavern;

import java.util.List;
import java.util.Map;

import com.github.cerealklla.settlemynts.bridge.LyfeTavernBridge;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.zone.PlotRecord;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * "New NPCs will show up every day" -- same real-day-boundary detection shape as {@code
 * zone.ShopMidnightRestockTicker}, but iterates every known Tavern Stool directly (via
 * {@link TavernStoolIndex}) rather than force-loading whole settlements -- same "known scaling
 * simplification" {@code guardhouse.GuardSpawnTicker} already explicitly accepts for Guard Kit
 * Stands: a stool whose chunk isn't currently loaded is silently skipped for that day's scan. This
 * is a deliberately lighter-weight choice than {@code ShopMidnightRestockTicker}'s own {@code
 * MidnightChunkWaker}/{@code MidnightWorkQueue} machinery (built specifically to survive heavy,
 * nested box-scanning work like {@code PlannedInventoryClearing}) -- rolling one NPC per empty stool
 * is cheap, one-shot, per-stool work with no equivalent watchdog-crash risk.
 *
 * <p>On each boundary: any still-unhired {@link TavernNpcEntity} is discarded first (user's explicit
 * choice -- "despawns next daily cycle, stool reopens"), then every now-empty stool (up to its
 * plot's own {@link TavernConstants#stoolCountForTier}) gets a freshly rolled NPC, mounted onto the
 * stool's real Lyfe seat via {@link LyfeTavernBridge#seatOnStool}.
 */
public final class TavernDailySpawnTicker {

    private static final long TICKS_PER_DAY = 24000L;

    // A few seconds' grace after boot before the one-time startup catch-up pass runs, so nearby
    // chunks/entities actually have time to load first -- an otherwise-eligible stool whose chunk
    // still isn't loaded even then is silently skipped, same accepted limitation as every other scan
    // here (see this class's own doc).
    private static final int STARTUP_DELAY_TICKS = 100;

    private long lastKnownDay = -1;
    private boolean startupPassRun = false;

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        long currentDay = Math.floorDiv(server.overworld().getOverworldClockTime(), TICKS_PER_DAY);
        if (lastKnownDay < 0) {
            lastKnownDay = currentDay;
        }
        boolean dayBoundaryCrossed = currentDay > lastKnownDay;
        // Runs once, shortly after each server (re)start, so a stool left empty by the restart (or
        // placed before this ticker's first real day boundary) gets filled right away instead of
        // waiting out a full in-game day -- a real bug, caught 2026-10-31 ("I don't see any NPCs
        // spawning"): this ticker previously only ever baselined on its first tick, never spawned.
        boolean startupCatchUp = !startupPassRun && server.getTickCount() >= STARTUP_DELAY_TICKS;
        if (!dayBoundaryCrossed && !startupCatchUp) {
            return;
        }
        lastKnownDay = currentDay;
        if (startupCatchUp) {
            startupPassRun = true;
        }

        if (!LyfeTavernBridge.isLoaded()) {
            return;
        }
        for (Map.Entry<GlobalPos, TavernStoolIndex.StoolInfo> entry : TavernStoolIndex.get(server).all().entrySet()) {
            GlobalPos pos = entry.getKey();
            ServerLevel level = server.getLevel(pos.dimension());
            if (level == null) {
                continue;
            }
            processStool(level, pos, entry.getValue());
        }
    }

    /**
     * Forces every known stool belonging to one plot through the exact same {@link #processStool}
     * logic the daily boundary/startup catch-up passes use, right now -- for {@code
     * debug.DebugCommands}' admin-only command, added 2026-11-01 so a plot stuck with stale/missing
     * NPCs (the race {@link #processStool} itself was just fixed for) doesn't have to wait on a real
     * day boundary lining up with someone standing nearby. Returns how many stools were actually
     * processed (chunk loaded, core/plot/tier all resolved) out of the plot's total known stools.
     */
    public static int forceRefreshPlot(MinecraftServer server, java.util.UUID settlementCoreId, java.util.UUID plotId) {
        if (!LyfeTavernBridge.isLoaded()) {
            return 0;
        }
        int processed = 0;
        for (Map.Entry<GlobalPos, TavernStoolIndex.StoolInfo> entry : TavernStoolIndex.get(server).all().entrySet()) {
            TavernStoolIndex.StoolInfo info = entry.getValue();
            if (!info.settlementCoreId().equals(settlementCoreId) || !info.plotId().equals(plotId)) {
                continue;
            }
            GlobalPos pos = entry.getKey();
            ServerLevel level = server.getLevel(pos.dimension());
            if (level == null) {
                continue;
            }
            if (processStool(level, pos, info)) {
                processed++;
            }
        }
        return processed;
    }

    /** @return true if this stool was actually resolved and processed (chunk loaded, core/plot/tier all resolved), false if it was silently skipped. */
    private static boolean processStool(ServerLevel level, GlobalPos pos, TavernStoolIndex.StoolInfo info) {
        // Real duplication bug, caught 2026-11-01: this method's own discard-then-spawn logic is
        // only trustworthy if the stool's own chunk is actually loaded right now -- level.getEntitiesOfClass
        // below can only ever see already-loaded entities, so an unloaded stool's existing NPC (sitting
        // in that chunk's saved data, not in memory) would be silently missed by the discard scan while
        // spawnNpc still went ahead regardless, stacking a fresh duplicate on top every single day
        // boundary for as long as nobody stood nearby. Skipping entirely whenever the stool's chunk
        // isn't loaded is exactly this class's own documented behavior ("silently skipped for that
        // day's scan") -- the bug was that only the core/plot resolution was ever actually gated on
        // load state, not the stool position itself.
        if (!level.isLoaded(pos.pos())) {
            return false;
        }
        if (!(level.getEntity(info.settlementCoreId()) instanceof GhostTownHallCoreEntity core)) {
            return false; // Chunk not loaded, or the core couldn't be resolved -- skip this scan.
        }
        PlotRecord plot = core.getPlots().stream().filter(p -> p.plotId().equals(info.plotId())).findFirst().orElse(null);
        if (plot == null) {
            return false;
        }

        // Real NPC-loss bug, caught 2026-11-01 ("previously when there were dupes they all had NPCs,
        // now only 2 do"): resolveTier below was being called AFTER the discard scan further down,
        // so a stool whose own chunk was loaded but whose Construction Box (routinely in a different
        // chunk -- it's placed just outside the plot's buildable area) was NOT yet loaded at this
        // exact tick would have resolveTier silently return 0, bailing out at the tier check with its
        // real NPC already discarded and nothing spawned to replace it -- permanently, until the next
        // full day boundary (20 real minutes) happens to land with the box resolvable too. This races
        // on every server restart's startup catch-up pass (chunk-load order isn't guaranteed), which
        // is exactly why two back-to-back redeploys today eroded this down to 2 of 5 stools instead of
        // all 5 keeping their (possibly duplicated) NPC. Resolved now, before anything is touched, so
        // an unresolvable tier leaves this stool's existing NPC(s) completely alone instead.
        int boxTier = ModList.get().isLoaded("blueprynts")
                ? com.github.cerealklla.settlemynts.bridge.BlueprintsConstructionBridge.resolveTier(level, plot)
                : 0;
        if (TavernConstants.stoolCountForTier(boxTier) <= 0) {
            return false;
        }

        TavernStoolIndex index = TavernStoolIndex.get(level.getServer());
        // Real duplication bug, caught 2026-10-31 ("several of the same NPCs on a single stool"):
        // looking up only the single stored seatedNpcId by UUID silently finds nothing if that
        // entity's chunk hadn't actually loaded yet at the moment this scan ran (its own UUID isn't
        // resolvable until the chunk is), but this method still went on to spawn a brand new NPC
        // regardless -- the old one, still physically there, was never removed. Every repeated
        // startup catch-up pass (one per server restart) could stack another one on top. Scanning the
        // stool's own position directly for any live TavernNpcEntity (not trusting the stored id at
        // all) and discarding everything found is robust regardless of index staleness, and also
        // cleans up any duplicates already created by the bug above the next time this runs.
        BlockPos stoolPos = pos.pos();
        AABB scanBox = new AABB(stoolPos).inflate(1.5);
        List<TavernNpcEntity> stale = level.getEntitiesOfClass(TavernNpcEntity.class, scanBox);
        for (TavernNpcEntity npc : stale) {
            npc.discard(); // Never hired -- despawns, stool reopens this same scan.
        }
        if (info.seatedNpcId().isPresent()) {
            index.clearSeatedNpc(pos);
        }

        spawnNpc(level, pos, info, boxTier);
        return true;
    }

    /**
     * Rolls and seats a fresh NPC on a currently-empty stool -- shared by the daily refresh above and
     * {@link TavernStoolItem#useOn}, which calls this directly the moment a stool is placed so a
     * freshly-placed stool doesn't sit empty until the next in-game day boundary (a real bug, caught
     * 2026-10-31: {@link #onServerTick}'s very first tick after each server start only ever baselines
     * {@link #lastKnownDay} without spawning, so newly placed stools showed nothing for up to a full
     * day).
     *
     * <p><b>No longer rolls starting gear, 2026-11-01</b> -- real report: a hired NPC's free rolled
     * loadout (up to a full Netherite set + shield at Tier 5) was extractable whole via the
     * Companion Token's own Equip GUI, letting a Gold hire requirement (as little as 100 Nuggets) buy
     * gear worth far more than the hire cost. {@code TavernGearRoller} is gone outright -- a hired
     * companion now starts with empty equipment slots, same as its stats stay real (health/attack
     * still rolled by {@link TavernStatRoller}, which isn't a tradeable item and carries no comparable
     * exploit).
     */
    static void spawnNpc(ServerLevel level, GlobalPos pos, TavernStoolIndex.StoolInfo info, int tier) {
        TavernNpcEntity npc = new TavernNpcEntity(
                com.github.cerealklla.settlemynts.registration.ModEntities.TAVERN_NPC.get(), level);
        var blockPos = pos.pos();
        npc.setPos(blockPos.getX() + 0.5, blockPos.getY(), blockPos.getZ() + 0.5);
        HireRequirement requirement = TavernHireRoller.rollRequirement(tier, level.getRandom());
        npc.setIdentity(pos, info.settlementCoreId(), info.plotId(), tier, requirement);
        TavernStatRoller.roll(npc, tier, level.getRandom());
        level.addFreshEntity(npc);
        LyfeTavernBridge.seatOnStool(level, blockPos, npc);
        TavernStoolIndex.get(level.getServer()).setSeatedNpc(pos, npc.getUUID());
    }
}
