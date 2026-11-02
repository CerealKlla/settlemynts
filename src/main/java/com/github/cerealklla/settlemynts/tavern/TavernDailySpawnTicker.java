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
import net.neoforged.fml.ModList;

/**
 * "New NPCs will show up every day."
 *
 * <p><b>Rewritten 2026-11-01</b> -- this used to be its own independent event-subscribed ticker,
 * detecting the real day boundary itself and silently skipping any stool whose chunk wasn't loaded
 * at that exact moment (explicitly flagged as a "lighter-weight" choice than {@code
 * zone.ShopMidnightRestockTicker}'s own force-load machinery). Real user correction: "the settlement
 * is supposed to come out of stasis at midnight to run a bunch of other stuff; this tavern npc
 * regeneration should happen at the same time so that even if no player is present the new npcs are
 * generated" -- that "lighter-weight" choice was exactly the bug, not an acceptable tradeoff: a
 * chunk-dependent scan can only ever work by coincidence, and the discard-before-respawn shape (see
 * {@link #processStool}'s own doc) turns a missed scan into a *permanent* loss, not just a skipped
 * one. This class is no longer an event subscriber at all -- {@code zone.ShopMidnightRestockTicker}
 * now calls {@link #processStool} directly from inside its own per-settlement, force-loaded,
 * {@code zone.MidnightWorkQueue}-paced pipeline (see that class's own doc), the same real "wake the
 * whole settlement, do every job, let it rest" mechanism every other midnight settlement job already
 * uses -- so Tavern stools get the exact same regardless-of-player-presence guarantee for free,
 * with no separate chunk-loading story of their own to get wrong.
 *
 * <p>Any still-unhired {@link TavernNpcEntity} is discarded first (user's explicit choice --
 * "despawns next daily cycle, stool reopens"), then every now-empty stool (up to its plot's own
 * {@link TavernConstants#stoolCountForTier}) gets a freshly rolled NPC, mounted onto the stool's real
 * Lyfe seat via {@link LyfeTavernBridge#seatOnStool}. A freshly-*placed* stool is handled separately,
 * immediately, by {@link TavernStoolItem#useOn} calling {@link #spawnNpc} directly -- it doesn't need
 * to wait for any midnight pass at all.
 */
public final class TavernDailySpawnTicker {

    private TavernDailySpawnTicker() {
    }

    /**
     * Forces every known stool belonging to one plot through {@link #processStool}, right now. Two
     * callers: {@code zone.ShopMidnightRestockTicker}'s own per-settlement midnight pipeline (the
     * real one, see this class's own top-level doc) -- called only after that pipeline has already
     * force-loaded every one of this plot's stool positions via {@code zone.MidnightChunkWaker}, so
     * every stool is guaranteed resolvable there, not merely likely to be; and {@code
     * debug.DebugCommands}' admin-only {@code tavernrefresh} command, added 2026-11-01 for a plot that
     * already lost NPCs before this class's chunk-dependent-race fix shipped and doesn't want to wait
     * for the next midnight pass to repair it -- there, a stool's chunk may or may not happen to be
     * loaded, same best-effort basis as before. Returns how many stools were actually processed
     * (chunk loaded, core/plot/tier all resolved) out of the plot's total known stools.
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
     * Rolls and seats a fresh NPC on a currently-empty stool -- shared by {@link #processStool} above
     * and {@link TavernStoolItem#useOn}, which calls this directly the moment a stool is placed so a
     * freshly-placed stool doesn't sit empty until the next midnight pass.
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
