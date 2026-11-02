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
import net.minecraft.world.entity.PathfinderMob;
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
 *
 * <p><b>Mount-aware, 2026-11-01 (real live-test report: "doesn't seem to be moving any faster than
 * just walking speed")</b> -- a mounted passenger's own {@code Navigation}/{@code MoveControl} never
 * actually move it at all; vanilla only ever applies a mob's own pathing to its real position while
 * it's NOT a passenger ({@code Entity#rideTick} drives position for a passenger instead), so the
 * companion issuing moves to its own navigation while riding a Mount was a complete no-op -- any
 * motion observed was purely the mount's own stock wander AI, not real travel toward the owner, which
 * is exactly why it looked capped at a slow, undirected walking pace. {@link #mover()} now resolves
 * to the companion's {@linkplain net.minecraft.world.entity.Entity#getVehicle() vehicle} instead of
 * the companion itself whenever it's riding one, so the actual navigating/moving entity is always
 * whichever one vanilla will really reposition.
 *
 * <p><b>Still too slow even mounted, same day (second real report)</b> -- confirmed via RCON that the
 * companion genuinely was riding its mount (a real NBT {@code Passengers} relationship), so the fix
 * above was working as designed -- the actual remaining gap is that it drives the mount through the
 * exact same plain {@code Navigation}/{@code moveTo} pathing any walking {@code Mob} uses, bounded by
 * the mount's own base {@code movement_speed} attribute (a horse's is only ~0.2-0.3) -- real galloping
 * speed only ever comes from vanilla's separate, Player-only {@code AbstractHorse#travel} steering
 * code path ({@code getControllingPassenger()} is hard-gated to {@code Player}, see this class's own
 * earlier entry), which a non-player rider can never reach no matter what it asks its own navigation
 * to do. Replicating that full steering/boost mechanic for a non-player rider is a real, separate
 * engineering effort, not a one-line fix -- as a pragmatic stand-in, a mounted companion now uses a
 * noticeably higher modifier (flagged placeholder/tunable, same footing as every other numeric curve
 * in this codebase) so it at least reads as meaningfully faster than walking, even though it's still
 * Navigation-driven rather than a true gallop.
 *
 * <p><b>Always galloping looked wrong once mounted, same day (third real report)</b> -- "it's moving
 * at max speed constantly while i'm just standing still." The mounted speed bump above applied
 * unconditionally to every leg of travel, including trivial few-block repositions right next to the
 * owner, which read as excessive. Explicit user spec: walk if the companion is within 20 blocks of
 * its owner, gallop otherwise. {@link #currentSpeedModifier(ServerPlayer)} now takes the owner and
 * only picks the higher mounted modifier past {@link #RUN_DISTANCE_BLOCKS}, using the same
 * on-foot-pace modifier as the close-range mounted case too -- re-evaluated fresh each time a new
 * target is picked ({@link #start()}), not continuously mid-path.
 *
 * <p><b>Teleport-when-stuck safety net, same day (fourth spec)</b> -- explicit user request: if a
 * fresh cycle starts ({@link #start()}, meaning the previous leg just ended, whether by real arrival
 * or {@code canContinueToUse()} giving up on a stuck/failed path) and {@link #mover()} is still past
 * {@link #TELEPORT_DISTANCE_BLOCKS} from the owner, it's assumed stuck -- rather than issue yet
 * another {@code Navigation} leg that may fail the same way, {@link #mover()} is snapped directly to
 * the freshly-picked target instead of pathing to it.
 */
public class CompanionFollowPlayerGoal extends Goal {

    private static final double FOLLOW_RADIUS_BLOCKS = 15.0;
    private static final double ARRIVE_DIST_SQ = 4.0;
    private static final int MAX_SAMPLE_ATTEMPTS = 10;

    /** Below this distance from the owner, a mounted companion just walks instead of galloping -- explicit user spec. */
    private static final double RUN_DISTANCE_BLOCKS = 20.0;

    /** Navigation speedModifier used while riding a mount and past {@link #RUN_DISTANCE_BLOCKS} from the owner -- see this class's own doc for why this can't just be the mount's real gallop speed. Flagged placeholder/tunable. */
    private static final double MOUNTED_SPEED_MODIFIER = 2.5;

    /** Starting a fresh cycle still this far from the owner means the previous leg didn't actually close the gap -- assumed stuck, teleport instead of pathing again. Explicit user spec. */
    private static final double TELEPORT_DISTANCE_BLOCKS = 30.0;

    private final TavernNpcEntity npc;
    private final double speedModifier;
    private BlockPos target;

    public CompanionFollowPlayerGoal(TavernNpcEntity npc, double speedModifier) {
        this.npc = npc;
        this.speedModifier = speedModifier;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    /** The entity that actually gets repositioned by pathing right now -- the companion's mount while riding one, otherwise the companion itself. */
    private PathfinderMob mover() {
        return npc.getVehicle() instanceof PathfinderMob mount ? mount : npc;
    }

    /** {@link #speedModifier} while on foot, or while mounted but close to {@code owner}; {@link #MOUNTED_SPEED_MODIFIER} while mounted and more than {@link #RUN_DISTANCE_BLOCKS} away. */
    private double currentSpeedModifier(ServerPlayer owner) {
        if (!(npc.getVehicle() instanceof PathfinderMob)) {
            return speedModifier;
        }
        return npc.distanceTo(owner) < RUN_DISTANCE_BLOCKS ? speedModifier : MOUNTED_SPEED_MODIFIER;
    }

    @Override
    public boolean canUse() {
        if (npc.stoolPos() != null) {
            return false;
        }
        if (!mover().getNavigation().isDone()) {
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
        if (target == null) {
            return;
        }
        if (npc.distanceTo(owner) > TELEPORT_DISTANCE_BLOCKS) {
            mover().snapTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
            return;
        }
        mover().getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, currentSpeedModifier(owner));
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
        return !mover().getNavigation().isDone(); // still actively en route; a stuck/failed path also ends here
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
