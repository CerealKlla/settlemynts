package com.github.cerealklla.settlemynts.rope;

import java.util.List;

import com.github.cerealklla.settlemynts.registration.ModEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;

/**
 * A real, permanent leash attachment point pinned to one real Rope Fence Post's block position.
 * Rope Fence rework, second pass (see decisions.md, 2026-09-29) -- replaces painted-tripwire rope
 * segments between placed posts with vanilla's own generic {@link Leashable} rendering ("I like the
 * visual of the normal rope between the posts," per the user, who also pointed out fences already
 * have a right-click-to-attach-a-rope mechanic worth reusing).
 *
 * <p>Vanilla's own {@code LeashFenceKnotEntity} can't be reused directly for this -- checked via
 * decompiled bytecode: it's only ever a leash *holder* (what {@code LeadItem} attaches a leashed mob
 * to), never itself {@link Leashable}, so it can't represent one end of a post-to-post rope on its
 * own. This class fills that gap: a minimal marker (a {@link Display.BlockDisplay} never given a
 * block state, so nothing renders except the leash line itself) that implements {@link Leashable}
 * directly and can be either end of a link -- {@code RopeFencePostItem} leashes the newer post's
 * anchor to the older one's (or vice versa when resuming from an open post).
 *
 * <p>One instance per real post that has ever participated in a link, found by exact position
 * ({@link #findAt}) rather than an id persisted on the post's own {@code RopeFencePostBlockEntity}
 * -- a post's position is fixed and unique, so a small AABB search is simpler and self-healing (a
 * missing anchor is just recreated on the next link attempt).
 */
public class RopeAnchorEntity extends Display.BlockDisplay implements Leashable {

    private LeashData leashData;

    public RopeAnchorEntity(EntityType<? extends RopeAnchorEntity> type, Level level) {
        super(type, level);
    }

    public static RopeAnchorEntity getOrCreate(ServerLevel level, BlockPos pos) {
        RopeAnchorEntity existing = findAt(level, pos);
        if (existing != null) {
            return existing;
        }
        RopeAnchorEntity anchor = new RopeAnchorEntity(ModEntities.ROPE_ANCHOR.get(), level);
        anchor.setPos(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        level.addFreshEntity(anchor);
        return anchor;
    }

    public static RopeAnchorEntity findAt(ServerLevel level, BlockPos pos) {
        AABB box = new AABB(pos).inflate(0.1);
        List<RopeAnchorEntity> found = level.getEntitiesOfClass(RopeAnchorEntity.class, box);
        return found.isEmpty() ? null : found.get(0);
    }

    @Override
    public LeashData getLeashData() {
        return leashData;
    }

    @Override
    public void setLeashData(LeashData leashData) {
        this.leashData = leashData;
    }

    @Override
    public void tick() {
        super.tick();
        if (level() instanceof ServerLevel serverLevel) {
            Leashable.tickLeash(serverLevel, this);
        }
    }

    // See zone.GhostPlotStakeEntity's identical override for why this is needed -- vanilla's own
    // default (12.0) would auto-break the leash before this mod's own pull-back mechanic ever gets a
    // chance to act, now that RopeConnections.MAX_ROPE_LENGTH_BLOCKS exceeds it.
    @Override
    public double leashSnapDistance() {
        return RopeConnections.LEASH_SNAP_DISTANCE_OVERRIDE;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        this.readLeashData(input);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        LeashData data = getLeashData();
        if (data != null) {
            this.writeLeashData(output, data);
        }
    }
}
