package com.github.cerealklla.settlemynts.tavern;

import java.util.Map;
import java.util.UUID;

import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.zone.PlotRecord;

import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * "New NPCs will show up every day" -- same real-day-boundary detection shape as {@code
 * zone.ShopMidnightRestockTicker}, but iterates every known {@link TavernStoolEntity} directly (via
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
 * plot's own {@link TavernConstants#stoolCountForTier}) gets a freshly rolled NPC.
 */
public final class TavernDailySpawnTicker {

    private static final long TICKS_PER_DAY = 24000L;

    private long lastKnownDay = -1;

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        long currentDay = Math.floorDiv(server.overworld().getOverworldClockTime(), TICKS_PER_DAY);
        if (lastKnownDay < 0) {
            lastKnownDay = currentDay;
            return;
        }
        if (currentDay <= lastKnownDay) {
            return;
        }
        lastKnownDay = currentDay;

        for (Map.Entry<UUID, GlobalPos> entry : TavernStoolIndex.get(server).all().entrySet()) {
            GlobalPos pos = entry.getValue();
            ServerLevel level = server.getLevel(pos.dimension());
            if (level == null) {
                continue;
            }
            processStool(level, entry.getKey());
        }
    }

    private void processStool(ServerLevel level, UUID stoolId) {
        if (!(level.getEntity(stoolId) instanceof TavernStoolEntity stool)
                || stool.settlementCoreId() == null
                || !(level.getEntity(stool.settlementCoreId()) instanceof GhostTownHallCoreEntity core)) {
            return; // Chunk not loaded, or the stool/core couldn't be resolved -- skip this scan.
        }
        PlotRecord plot = core.getPlots().stream().filter(p -> p.plotId().equals(stool.plotId())).findFirst().orElse(null);
        if (plot == null) {
            return;
        }

        if (stool.seatedNpcId().isPresent()) {
            if (level.getEntity(stool.seatedNpcId().get()) instanceof TavernNpcEntity npc) {
                npc.discard(); // Never hired -- despawns, stool reopens this same scan.
            }
            stool.clearSeatedNpcId();
        }

        int boxTier = ModList.get().isLoaded("blueprynts")
                ? com.github.cerealklla.settlemynts.bridge.BlueprintsConstructionBridge.resolveTier(level, plot)
                : 0;
        if (TavernConstants.stoolCountForTier(boxTier) <= 0) {
            return;
        }
        spawnNpc(level, stool, boxTier);
    }

    private void spawnNpc(ServerLevel level, TavernStoolEntity stool, int tier) {
        TavernNpcEntity npc = new TavernNpcEntity(
                com.github.cerealklla.settlemynts.registration.ModEntities.TAVERN_NPC.get(), level);
        var pos = stool.position();
        npc.setPos(pos.x, pos.y, pos.z);
        HireRequirement requirement = TavernHireRoller.rollRequirement(tier, level.getRandom());
        npc.setIdentity(stool.getUUID(), stool.settlementCoreId(), stool.plotId(), tier, requirement);
        TavernGearRoller.equip(npc, tier);
        TavernStatRoller.roll(npc, tier, level.getRandom());
        level.addFreshEntity(npc);
        stool.setSeatedNpcId(npc.getUUID());
    }
}
