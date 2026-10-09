package com.github.cerealklla.settlemynts.resident;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Respawns a natural village's dead villagers ~5 minutes after death, Part D of the "Natural
 * settlements..." plan, 2026-10-08. Same periodic-scan shape as {@code
 * resident.ResidentSpawnTicker}/{@code guardhouse.GuardSpawnTicker} -- for any {@link
 * PendingVillagerRespawn} whose target tick has passed AND whose chunk is currently loaded, spawn a
 * fresh villager and drop the entry; everything else is left for a later scan. This single scan
 * naturally covers both "the chunk never unloaded, 5 minutes just passed" and "the chunk was
 * unloaded and only just came back" -- no separate chunk-load hook needed, matching how the existing
 * tickers already treat "no-op if unloaded, pick back up next scan" as their own de facto stasis
 * handling.
 */
public final class VillagerRespawnTicker {

    private static final int SCAN_INTERVAL_TICKS = 100; // 5 seconds, same cadence as ResidentSpawnTicker.

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % SCAN_INTERVAL_TICKS != 0) {
            return;
        }

        PendingVillagerRespawnIndex index = PendingVillagerRespawnIndex.get(server);
        List<PendingVillagerRespawn> due = new ArrayList<>();
        for (PendingVillagerRespawn entry : index.all()) {
            if (server.getTickCount() >= entry.respawnAtTick()) {
                due.add(entry);
            }
        }

        for (PendingVillagerRespawn entry : due) {
            ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, entry.dimension()));
            if (level == null || !level.hasChunk(entry.deathPos().getX() >> 4, entry.deathPos().getZ() >> 4)) {
                continue; // Chunk not loaded (or dimension gone) -- try again next scan.
            }
            BlockPos pos = entry.deathPos();
            Villager villager = new Villager(EntityType.VILLAGER, level);
            villager.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            level.addFreshEntity(villager);
            index.remove(entry);
        }
    }
}
