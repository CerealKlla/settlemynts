package com.github.cerealklla.settlemynts.guardhouse;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Real report, 2026-10-30, same day as {@link GuardReturnToDimensionGoal}'s own portal-crossing
 * fix and the identical issue found in Caryvan's own Wagon-follows-portal feature: "it seems like
 * the same problem is happening to the NPC/Guards; when they arrive [they're] already inside the
 * portal so can't teleport out again." Root cause confirmed against the real decompiled {@code
 * NetherPortalBlock#createDimensionTransition}: vanilla deliberately lands a solo entity exiting a
 * portal literally inside the portal's own block plane, relying only on portal cooldown to prevent
 * an instant re-trigger, not any real distance from the frame -- fine for a player (a trivial WASD
 * step clears it), but a {@code Mob}'s own pathfinding can get genuinely stuck trying to navigate
 * out of a tight, non-solid portal interior with no active goal specifically directing it away.
 *
 * <p>Fixed the same way as Caryvan's own Wagon fix (not duplicated code -- a separate mod, no
 * shared dependency for a helper this small, matching this suite's own established "copy and
 * tweak" precedent for small per-mod physics helpers): on {@link EntityJoinLevelEvent} for a
 * {@link GuardEntity}, scan the immediate area for a real portal block and, if found, nudge the
 * guard a safe distance away along the axis PERPENDICULAR to the portal's own {@code
 * HORIZONTAL_AXIS} (the direction an entity actually walks through it) via a plain {@code
 * teleportTo} -- not the guard's own post-teleport yaw, which vanilla only ever sets as a 0/90
 * degree axis-relative twist from the entry side, not a guaranteed "facing away from the portal."
 *
 * <p><b>Gated on {@link EntityJoinLevelEvent#loadedFromDisk()} being {@code false}</b> -- a real
 * concern worth flagging explicitly: a Guardhouse is routinely built right next to a settlement's
 * own portal (the exact scenario {@link GuardReturnToDimensionGoal} itself was built for), so a
 * guard legitimately patrolling near a stationary portal has its chunk reload constantly as players
 * come and go. {@code loadedFromDisk() == true} is precisely that ordinary case (an already-saved
 * guard being re-added from chunk NBT) and must be left alone, or every reload would yank a
 * perfectly fine, on-duty guard away from its post. {@code false} covers both a fresh spawn and the
 * one real target case -- the brand-new entity instance {@code Entity#teleportCrossDimension}
 * creates for a portal crossing, confirmed via the real decompiled source to never go through the
 * disk-load path at all.
 */
public final class GuardPortalClearanceListener {

    private static final double CLEARANCE_BLOCKS = 4.0D;
    private static final int PORTAL_SCAN_RADIUS = 3;

    @SubscribeEvent
    public void onJoin(final EntityJoinLevelEvent event) {
        if (event.loadedFromDisk()) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!(event.getEntity() instanceof GuardEntity guard) || !guard.isAlive()) {
            return;
        }
        final Direction.Axis portalAxis = findNearbyPortalAxis(level, guard.blockPosition());
        if (portalAxis == null) {
            return;
        }

        final Vec3 origin = guard.position();
        Vec3 step = portalAxis == Direction.Axis.X ? new Vec3(0.0D, 0.0D, 1.0D) : new Vec3(1.0D, 0.0D, 0.0D);
        final BlockPos stepPos = BlockPos.containing(origin.add(step.scale(CLEARANCE_BLOCKS)));
        if (level.getBlockState(stepPos).getBlock() instanceof Portal) {
            step = step.scale(-1.0D);
        }
        final Vec3 clearPos = origin.add(step.scale(CLEARANCE_BLOCKS));
        guard.teleportTo(clearPos.x, clearPos.y, clearPos.z);
    }

    private Direction.Axis findNearbyPortalAxis(final ServerLevel level, final BlockPos center) {
        for (int dx = -PORTAL_SCAN_RADIUS; dx <= PORTAL_SCAN_RADIUS; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -PORTAL_SCAN_RADIUS; dz <= PORTAL_SCAN_RADIUS; dz++) {
                    final BlockState state = level.getBlockState(center.offset(dx, dy, dz));
                    if (state.getBlock() instanceof Portal && state.hasProperty(BlockStateProperties.HORIZONTAL_AXIS)) {
                        return state.getValue(BlockStateProperties.HORIZONTAL_AXIS);
                    }
                }
            }
        }
        return null;
    }
}
