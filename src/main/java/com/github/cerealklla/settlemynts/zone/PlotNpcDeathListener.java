package com.github.cerealklla.settlemynts.zone;

import com.github.cerealklla.settlemynts.SettlemyntsMod;
import com.github.cerealklla.settlemynts.farm.FarmerWorkerEntity;
import com.github.cerealklla.settlemynts.lumberyard.LumberjackWorkerEntity;
import com.github.cerealklla.settlemynts.mineshaft.MinerWorkerEntity;
import com.github.cerealklla.settlemynts.plotsign.PlotConfigSignBlockEntity;
import com.github.cerealklla.settlemynts.plotsign.PlotConfigSignIndex;
import com.github.cerealklla.settlemynts.resident.ResidentVillagerEntity;

import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * Clears a plot's own worker-id slot the instant its {@link PlotNpc} actually dies -- added
 * 2026-10-12, the real fix for a confirmed-live duplicate-worker bug ("a second miner has now
 * spawned," same plot both times, user watched the original cleanup personally so this was a fresh
 * recurrence, not a leftover). Root cause, found via the temporary {@code [MinerSpawnDebug]}
 * logging each spawn ticker already carried: every single duplicate spawn in the logs was preceded
 * by "level.getEntity() returned null" for the slot's own recorded UUID -- {@code
 * ServerLevel#getEntity(UUID)} only ever finds an entity in a currently *loaded* chunk, and a plot
 * worker routinely wanders away from its own spawn/sign chunk (a Miner especially, following ore
 * through a cave system) into a neighboring chunk that hasn't reloaded yet right after a restart.
 * Every spawn ticker (`ResidentSpawnTicker`/`LumberjackSpawnTicker`/`FarmerSpawnTicker`/
 * `MineshaftSpawnTicker`) used exactly that "not currently findable" check as its own proof the
 * worker had died -- which is only ever proof it's not loaded *right now*, not that it's actually
 * gone. The real, original worker then reloads moments later once its own chunk catches up,
 * duplicating whatever the ticker just spawned in its place.
 *
 * <p>The fix: stop inferring death from loaded-chunk lookup entirely. A worker's slot is now only
 * ever cleared here, on a real {@link LivingDeathEvent} for that exact entity -- the one unambiguous
 * signal that it's actually gone. Each spawn ticker now just checks whether its slot UUID is
 * {@code null}, full stop; it no longer touches {@code level.getEntity()} to decide liveness at
 * all. The tradeoff, accepted rather than engineered around: if a worker is ever removed by
 * something other than a real death (e.g. corrupted save data silently dropping the entity), its
 * slot would stay stuck non-null with no worker and no automatic respawn -- a quiet, easily-noticed,
 * easily-fixed failure mode, and a much better one than silently duplicating on every restart.
 */
public final class PlotNpcDeathListener {

    @SubscribeEvent
    public void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof PlotNpc npc) || npc.plotId() == null) {
            return;
        }
        if (!(event.getEntity().level() instanceof ServerLevel level)) {
            return;
        }
        // TEMPORARY diagnostic logging (2026-10-12) -- see farm.FarmerSpawnTicker's own doc for the
        // real live report this is chasing.
        SettlemyntsMod.LOGGER.info("[FarmerSpawnDebug] PlotNpc death: entity={} uuid={} plotId={} indexHasEntry={}",
                event.getEntity().getClass().getSimpleName(), event.getEntity().getUUID(), npc.plotId(),
                PlotConfigSignIndex.get(level.getServer()).get(npc.plotId()).isPresent());
        PlotConfigSignIndex.get(level.getServer()).get(npc.plotId()).ifPresent(pos -> {
            GlobalPos signPos = pos;
            ServerLevel signLevel = level.getServer().getLevel(signPos.dimension());
            if (signLevel == null || !(signLevel.getBlockEntity(signPos.pos()) instanceof PlotConfigSignBlockEntity sign)) {
                SettlemyntsMod.LOGGER.info("[FarmerSpawnDebug] death handler couldn't resolve the sign block entity at {}", signPos);
                return;
            }
            if (event.getEntity() instanceof ResidentVillagerEntity) {
                sign.setResidentVillagerId(null);
            } else if (event.getEntity() instanceof LumberjackWorkerEntity) {
                sign.setLumberjackWorkerId(null);
            } else if (event.getEntity() instanceof FarmerWorkerEntity) {
                SettlemyntsMod.LOGGER.info("[FarmerSpawnDebug] clearing farmerWorkerId on sign at {} (was {})", signPos, sign.farmerWorkerId());
                sign.setFarmerWorkerId(null);
            } else if (event.getEntity() instanceof MinerWorkerEntity) {
                sign.setMinerWorkerId(null);
            }
        });
    }
}
