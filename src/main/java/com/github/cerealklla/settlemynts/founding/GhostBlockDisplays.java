package com.github.cerealklla.settlemynts.founding;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;

/**
 * Sets a freshly-created {@link Display.BlockDisplay}'s block state -- {@code setBlockState} itself
 * is private on that class (only settable via its own NBT load path, the same mechanism vanilla's
 * own {@code /summon minecraft:block_display} command uses), so this goes through {@link
 * Entity#load(ValueInput)} with a hand-built {@code block_state} tag rather than reflection.
 *
 * <p>Added 2026-09-27 (see decisions.md same date) -- every ghost boundary/fence-post marker in
 * this mod used to float a held-item-style icon ({@code ItemModelResolver}/{@code
 * ItemDisplayContext.GROUND}), which renders at dropped-item scale/orientation, not a real
 * full-size block, and (for the fence-post preview specifically) never shows a fence's own
 * connected-post model since item rendering never touches a block's multipart model at all. Vanilla's
 * {@code Display.BlockDisplay} entity is the real, sanctioned way to show an actual block model
 * (correct scale, and a fence's model correctly reads whatever connection properties the given
 * {@link BlockState} itself carries) as a non-solid, walk-through, per-player-visible marker.
 */
public final class GhostBlockDisplays {

    private GhostBlockDisplays() {
    }

    public static void setBlockState(ServerLevel level, Entity entity, BlockState state) {
        Tag encoded = BlockState.CODEC.encodeStart(NbtOps.INSTANCE, state).result()
                .orElseThrow(() -> new IllegalStateException("Failed to encode block state " + state));
        CompoundTag wrapper = new CompoundTag();
        wrapper.put(Display.BlockDisplay.TAG_BLOCK_STATE, encoded);
        ValueInput input = TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), wrapper);
        entity.load(input);
    }
}
