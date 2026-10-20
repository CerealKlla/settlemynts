package com.github.cerealklla.settlemynts.npc;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Shared "stuck on top of a tree" safety net for this suite's custom ground NPCs -- extracted
 * 2026-10-20 from {@code guardhouse.GuardEntity}'s own 2026-10-19 fix (see that class's own doc for
 * the full root-cause writeup: the 2026-10-17 {@code PathType.LEAVES} malus only stops a mob from
 * pathing THROUGH a tree's interior, not from climbing UP ONTO its flat canopy top, which reads as
 * ordinary walkable ground). Real report, 2026-10-20: another NPC (not a Guard) found stuck the same
 * way, confirming the gap flagged but not fixed in Guard's own decisions.md entry actually bit live.
 * Checked directly against the block underfoot rather than inferring from height alone, since a
 * legitimate multi-story building's upper floor is also "above the no-leaves surface reading" (that
 * heightmap reports the roof, not each floor) but is never standing on leaves.
 */
public final class NpcTreetopEscape {

    private NpcTreetopEscape() {
    }

    public static void check(Mob npc, ServerLevel level) {
        int blockX = Mth.floor(npc.getX());
        int blockZ = Mth.floor(npc.getZ());
        BlockPos feetPos = new BlockPos(blockX, Mth.floor(npc.getY()), blockZ);
        if (level.getBlockState(feetPos).is(BlockTags.LEAVES) || level.getBlockState(feetPos.below()).is(BlockTags.LEAVES)) {
            int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ);
            npc.getNavigation().stop();
            npc.teleportTo(blockX + 0.5, surfaceY, blockZ + 0.5);
        }
    }
}
