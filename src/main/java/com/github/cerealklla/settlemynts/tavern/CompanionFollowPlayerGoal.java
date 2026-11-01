package com.github.cerealklla.settlemynts.tavern;

import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

import com.github.cerealklla.settlemynts.bridge.LyfeSummoningBridge;
import com.github.cerealklla.settlemynts.util.GroundHeightFinder;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * A summoned Tavern Companion's own "stay near the owner" behavior -- explicit user spec, 2026-11-01:
 * "The companion should pick a location near the player and set that as their path goal. Once they
 * arrive, repeat... within 15 blocks of the player's current position." Direct structural mirror of
 * {@code guardhouse.GuardPatrolAreaGoal}'s own fallback plot-wander (pick a sampled point, move
 * there, re-pick on arrival) with the road-network routing dropped entirely -- a companion has no
 * plot/road network of its own to patrol, just an owning player to stay near. Only ever active while
 * {@link TavernNpcEntity#stoolPos()} is {@code null} (see {@link CompanionSightGoal}'s own doc for
 * why that's the right gate) and the owner (resolved via {@link LyfeSummoningBridge#resolveCompanionOwner})
 * is online in the same dimension -- otherwise this goal simply does nothing, same as a Guard with no
 * roads/plot to fall back on.
 */
public class CompanionFollowPlayerGoal extends Goal {

    private static final double FOLLOW_RADIUS_BLOCKS = 15.0;
    private static final double ARRIVE_DIST_SQ = 4.0;
    private static final int MAX_SAMPLE_ATTEMPTS = 10;

    private final TavernNpcEntity npc;
    private final double speedModifier;
    private BlockPos target;

    public CompanionFollowPlayerGoal(TavernNpcEntity npc, double speedModifier) {
        this.npc = npc;
        this.speedModifier = speedModifier;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (npc.stoolPos() != null) {
            return false;
        }
        if (!npc.getNavigation().isDone()) {
            return false;
        }
        return resolveOwner().isPresent();
    }

    @Override
    public void start() {
        ServerPlayer owner = resolveOwner().orElse(null);
        if (owner == null || !(npc.level() instanceof ServerLevel level)) {
            target = null;
            return;
        }
        target = pickTargetNear(level, owner);
        if (target != null) {
            npc.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, speedModifier);
        }
    }

    @Override
    public void stop() {
        target = null;
    }

    @Override
    public boolean canContinueToUse() {
        if (target == null) {
            return false;
        }
        double dx = npc.getX() - (target.getX() + 0.5);
        double dz = npc.getZ() - (target.getZ() + 0.5);
        if ((dx * dx + dz * dz) <= ARRIVE_DIST_SQ) {
            return false; // arrived -- end this run, canUse() picks a fresh point next cycle
        }
        return !npc.getNavigation().isDone(); // still actively en route; a stuck/failed path also ends here
    }

    private Optional<ServerPlayer> resolveOwner() {
        Optional<UUID> ownerId = LyfeSummoningBridge.resolveCompanionOwner(npc);
        if (ownerId.isEmpty() || !(npc.level() instanceof ServerLevel level)) {
            return Optional.empty();
        }
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(ownerId.get());
        return player != null && player.level() == level ? Optional.of(player) : Optional.empty();
    }

    private BlockPos pickTargetNear(ServerLevel level, ServerPlayer owner) {
        int ownerX = Mth.floor(owner.getX());
        int ownerZ = Mth.floor(owner.getZ());
        for (int attempt = 0; attempt < MAX_SAMPLE_ATTEMPTS; attempt++) {
            int offsetX = npc.getRandom().nextInt((int) (FOLLOW_RADIUS_BLOCKS * 2) + 1) - (int) FOLLOW_RADIUS_BLOCKS;
            int offsetZ = npc.getRandom().nextInt((int) (FOLLOW_RADIUS_BLOCKS * 2) + 1) - (int) FOLLOW_RADIUS_BLOCKS;
            int x = ownerX + offsetX;
            int z = ownerZ + offsetZ;
            if (offsetX * offsetX + offsetZ * offsetZ > FOLLOW_RADIUS_BLOCKS * FOLLOW_RADIUS_BLOCKS) {
                continue;
            }
            int y = GroundHeightFinder.findNear(level, x, z, Mth.floor(owner.getY()));
            return new BlockPos(x, y, z);
        }
        return null;
    }
}
