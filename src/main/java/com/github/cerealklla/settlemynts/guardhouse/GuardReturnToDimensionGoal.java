package com.github.cerealklla.settlemynts.guardhouse;

import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/**
 * Recalls a Guard straight back to its home Guard Kit Stand the instant it's found in the wrong
 * dimension with nothing left to fight or heal. Originally (2026-10-29) built to path the guard to
 * the nearest portal and let it walk/cross naturally -- see this class's own real git history for
 * that version's full writeup -- but **rebuilt into a direct, portal-free recall on 2026-11-13**
 * per an explicit, absolute instruction: "NOTHING should be able to go through a portal now unless
 * it's a player right clicking... No wild mobs, nothing else can ever go through End and Nether
 * portals." That's a hard reversal of the original design's own stated goal ("deliberately NOT
 * portal-blocking/immune... has to stay possible, since the user explicitly wants to keep the door
 * open for a future Nether trade route") -- the new rule wins; a guard must never interact with a
 * portal block at all, crossing or otherwise, so {@code nuclyus.api.Nuclyus#attemptPortalCrossing}
 * is no longer called here, and neither is any portal lookup/pathing.
 *
 * <p>Practically, this also makes the scenario this goal exists for close to unreachable going
 * forward: Nuclyus's {@code portal.VanillaPortalAutoTeleportListener} now blocks literally any
 * non-player entity (including whatever a Guard might be chasing) from auto-crossing a portal at
 * all, so a Guard can no longer end up astray by chasing a target through one the way the original
 * 2026-10-29 report described. This goal is kept anyway, as a cheap defensive backstop for an
 * already-stranded guard on an existing save (from before this change), or any other future way a
 * guard's dimension and its home might diverge -- not deleted outright, per the general principle
 * that a standing safety net costing nothing per-tick is worth keeping even once its original
 * trigger is gone.
 *
 * <p>{@link GuardEntity#getTarget()} doubles as the "actively engaged" signal for both combat AND
 * healing (see {@code GuardSpellCastGoal#engage}'s own doc -- it sets the same field either way),
 * so checking for "no target" alone already covers "not fighting or healing allies," same as the
 * original design.
 *
 * <p>Home position/dimension resolves from {@link GuardKitStandIndex} -- the real {@link
 * GlobalPos} (dimension + block position) of the guard's own assigned stand, independent of chunk-
 * load state or where the guard itself currently is (see {@link #resolveHomeFromStand}'s own doc
 * for the migration case of a guard saved before {@link GuardEntity#homeDimension()} existed).
 * Falls back to a safe position at the home dimension's origin (its own heightmap surface at
 * 0,0) if the stand itself can't be resolved (e.g. picked up/removed) but the dimension is still
 * known.
 */
public final class GuardReturnToDimensionGoal extends Goal {

    private final GuardEntity guard;

    public GuardReturnToDimensionGoal(GuardEntity guard) {
        this.guard = guard;
        setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        return isStrandedWithNoTarget();
    }

    @Override
    public boolean canContinueToUse() {
        // One-shot -- start() performs the recall synchronously, nothing left to continue ticking.
        return false;
    }

    /**
     * A guard saved before {@link GuardEntity#homeDimension} existed has {@code null} there.
     * Resolves the real home dimension from an authoritative source instead of a guess: {@link
     * GuardKitStandIndex} already tracks every stand's real {@link GlobalPos} (dimension included)
     * independent of chunk-load state or where the guard itself currently is -- the same reason
     * that index exists at all. Falls back to the guard's current dimension only if the stand
     * itself can't be resolved (e.g. picked up/removed).
     */
    private boolean isStrandedWithNoTarget() {
        if (!(guard.level() instanceof ServerLevel level) || guard.getTarget() != null) {
            return false;
        }
        if (guard.homeDimension() == null) {
            guard.setHomeDimension(resolveHomeFromStand().map(GlobalPos::dimension).orElse(level.dimension()));
        }
        return level.dimension() != guard.homeDimension();
    }

    private Optional<GlobalPos> resolveHomeFromStand() {
        UUID standId = guard.assignedStandId();
        if (standId == null || !(guard.level() instanceof ServerLevel level)) {
            return Optional.empty();
        }
        return GuardKitStandIndex.get(level.getServer()).get(standId);
    }

    @Override
    public void start() {
        if (!(guard.level() instanceof ServerLevel currentLevel)) {
            return;
        }
        Optional<GlobalPos> stand = resolveHomeFromStand();
        ResourceKey<Level> homeDimension = stand.map(GlobalPos::dimension).orElse(guard.homeDimension());
        if (homeDimension == null) {
            return;
        }
        ServerLevel homeLevel = currentLevel.getServer().getLevel(homeDimension);
        if (homeLevel == null) {
            return;
        }
        BlockPos homePos = stand.map(GlobalPos::pos)
                .orElseGet(() -> homeLevel.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, BlockPos.ZERO));
        Vec3 target = Vec3.atBottomCenterOf(homePos);
        guard.teleport(new TeleportTransition(
                homeLevel, target, Vec3.ZERO, guard.getYRot(), 0.0F, TeleportTransition.DO_NOTHING));
    }

    @Override
    public void stop() {
    }
}
