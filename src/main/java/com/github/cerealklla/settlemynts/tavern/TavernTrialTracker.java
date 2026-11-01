package com.github.cerealklla.settlemynts.tavern;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.Enemy;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * "Do a timed quest quickly" ({@link HireRequirement.TrialRequirement}) -- a self-contained,
 * Settlemynts-local Trial, deliberately NOT routed through Myssions' quest system (see design doc
 * Section 17's own flagged scope call: Myssions has no primitive for an NPC to originate an ad-hoc
 * quest tied to one specific interaction yet, and building one there is its own mini-project outside
 * this slice). Concretely: defeat {@code requiredKills} hostile mobs within {@code timeLimitTicks}
 * of accepting. At most one active Trial per player at a time -- starting a new one (right-clicking
 * a different Trial NPC, or re-accepting after this one expired) simply overwrites it.
 */
public final class TavernTrialTracker {

    private record ActiveTrial(UUID npcId, int requiredKills, long deadlineGameTime, int killsSoFar) {
        ActiveTrial withKill() {
            return new ActiveTrial(npcId, requiredKills, deadlineGameTime, killsSoFar + 1);
        }
    }

    private static final Map<UUID, ActiveTrial> active = new ConcurrentHashMap<>();

    private TavernTrialTracker() {
    }

    /** Right-clicking a Trial NPC -- starts a fresh Trial if none is active for this NPC, otherwise reports/resolves progress. */
    public static void onInteract(ServerPlayer player, ServerLevel level, TavernNpcEntity npc, HireRequirement.TrialRequirement trial) {
        ActiveTrial current = active.get(player.getUUID());
        if (current == null || !current.npcId().equals(npc.getUUID())) {
            active.put(player.getUUID(), new ActiveTrial(npc.getUUID(), trial.requiredKills(),
                    level.getGameTime() + trial.timeLimitTicks(), 0));
            player.sendSystemMessage(Component.literal("Trial started: " + trial.describe().getString()
                    + " -- come back and right-click " + npc.getName().getString() + " again once done, or if you run out of time."));
            return;
        }
        if (level.getGameTime() > current.deadlineGameTime()) {
            active.remove(player.getUUID());
            player.sendSystemMessage(Component.literal("You ran out of time. Right-click again to start a new Trial."));
            return;
        }
        if (current.killsSoFar() < current.requiredKills()) {
            player.sendSystemMessage(Component.literal("Trial in progress: " + current.killsSoFar() + "/" + current.requiredKills() + " kills."));
            return;
        }
        active.remove(player.getUUID());
        TavernHireHandler.completeHire(player, level, npc);
    }

    /** A hostile-mob kill while a Trial is active counts toward it -- {@code LivingEntity#getLastHurtByPlayer} covers delayed-kill cases (fire, etc.), same convention Lyfe's own Cook skill uses for burn-kill attribution. */
    public static final class TrialKillListener {
        @SubscribeEvent
        public void onDeath(LivingDeathEvent event) {
            if (!(event.getEntity() instanceof Enemy)) {
                return;
            }
            if (!(event.getEntity().getLastHurtByPlayer() instanceof ServerPlayer killer)) {
                return;
            }
            ActiveTrial current = active.get(killer.getUUID());
            if (current == null) {
                return;
            }
            if (killer.level().getGameTime() > current.deadlineGameTime()) {
                return;
            }
            ActiveTrial updated = current.withKill();
            active.put(killer.getUUID(), updated);
            if (updated.killsSoFar() >= updated.requiredKills()) {
                killer.sendSystemMessage(Component.literal("Trial complete! Right-click the NPC to collect your hire."));
            }
        }
    }
}
