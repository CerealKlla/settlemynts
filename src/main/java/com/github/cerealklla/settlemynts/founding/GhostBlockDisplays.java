package com.github.cerealklla.settlemynts.founding;

import java.lang.reflect.Field;

import org.joml.Vector3f;
import org.joml.Vector3fc;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Display;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Sets a freshly-created {@link Display.BlockDisplay}'s block state -- {@code setBlockState} itself
 * is private on that class, and its own synced-data accessor ({@code DATA_BLOCK_STATE_ID}) is a
 * private static field, so this resolves it once via reflection (cached) and writes it straight
 * into the entity's own (inherited, {@code protected}) {@link SynchedEntityData}.
 *
 * <p><b>Deliberately not {@code Entity#load(ValueInput)}</b> -- an earlier version of this class
 * built a one-off {@code block_state} NBT tag and called {@code entity.load(...)}, the same
 * mechanism vanilla's own {@code /summon minecraft:block_display} command uses. That was a real,
 * silent bug (found via live playtest, 2026-09-27 -- "not actually seeing the perimeter walls or
 * the fences"): {@code Entity#load} is the *general* "restore this entity from saved NBT" method,
 * and unconditionally calls {@code setPosRaw(pos.x, pos.y, pos.z)} with {@code pos} defaulting to
 * {@code Vec3.ZERO} when no "Pos" tag is present -- confirmed by reading the decompiled source.
 * Since {@code create()} calls this *after* {@code setPos(...)}, every marker was silently
 * teleported to the world origin the instant its block state was set, with no exception thrown.
 * Setting the synced data field directly touches only the one property it's meant to.
 *
 * <p>Added 2026-09-27 (see decisions.md) -- every ghost boundary/fence-post marker in this mod
 * used to float a held-item-style icon ({@code ItemModelResolver}/{@code
 * ItemDisplayContext.GROUND}), which renders at dropped-item scale/orientation, not a real
 * full-size block, and (for the fence-post preview specifically) never shows a fence's own
 * connected-post model since item rendering never touches a block's multipart model at all.
 * Vanilla's {@code Display.BlockDisplay} entity is the real, sanctioned way to show an actual
 * block model (correct scale, and a fence's model correctly reads whatever connection properties
 * the given {@link BlockState} carries) as a non-solid, walk-through, per-player-visible marker.
 */
public final class GhostBlockDisplays {

    private static volatile EntityDataAccessor<BlockState> blockStateAccessor;
    private static volatile EntityDataAccessor<Vector3fc> translationAccessor;

    private GhostBlockDisplays() {
    }

    public static void setBlockState(Display.BlockDisplay entity, BlockState state) {
        entity.getEntityData().set(blockStateAccessor(), state);
    }

    /**
     * Shifts where the block model visually renders, independent of the entity's own actual
     * position -- used by {@code zone.GhostPlotStakeEntity} (2026-09-29, live report: "very difficult
     * to right click") to reconcile a real, unavoidable mismatch: a plain block-aligned entity
     * position (e.g. matching a real placed block's own corner, the convention every other marker in
     * this class already uses) renders the visual spanning {@code [pos, pos+1]} on every axis, but
     * {@link net.minecraft.world.entity.Entity}'s own interaction bounding box is always *centered*
     * on the entity's X/Z position -- there's no vanilla way to make that box corner-anchored
     * instead. Storing the entity's position offset by +0.5 on X/Z (so its centered hitbox exactly
     * covers the intended block cell) and then compensating here with a -0.5 render translation
     * keeps the *visual* exactly where it always was, while finally giving the hitbox a correct,
     * non-lopsided footprint.
     */
    public static void setTranslation(Display entity, float x, float y, float z) {
        entity.getEntityData().set(translationAccessor(), new Vector3f(x, y, z));
    }

    @SuppressWarnings("unchecked")
    private static EntityDataAccessor<BlockState> blockStateAccessor() {
        EntityDataAccessor<BlockState> accessor = blockStateAccessor;
        if (accessor == null) {
            synchronized (GhostBlockDisplays.class) {
                accessor = blockStateAccessor;
                if (accessor == null) {
                    try {
                        Field field = Display.BlockDisplay.class.getDeclaredField("DATA_BLOCK_STATE_ID");
                        field.setAccessible(true);
                        accessor = (EntityDataAccessor<BlockState>) field.get(null);
                        blockStateAccessor = accessor;
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException("Could not resolve Display.BlockDisplay's block state accessor", e);
                    }
                }
            }
        }
        return accessor;
    }

    @SuppressWarnings("unchecked")
    private static EntityDataAccessor<Vector3fc> translationAccessor() {
        EntityDataAccessor<Vector3fc> accessor = translationAccessor;
        if (accessor == null) {
            synchronized (GhostBlockDisplays.class) {
                accessor = translationAccessor;
                if (accessor == null) {
                    try {
                        Field field = Display.class.getDeclaredField("DATA_TRANSLATION_ID");
                        field.setAccessible(true);
                        accessor = (EntityDataAccessor<Vector3fc>) field.get(null);
                        translationAccessor = accessor;
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException("Could not resolve Display's translation accessor", e);
                    }
                }
            }
        }
        return accessor;
    }
}
