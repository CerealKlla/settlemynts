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
 *
 * <p><b>Elevation guard added 2026-10-21</b> -- real regression found the very next day: a Lumberjack
 * worker (whose entire job is standing right next to a tree's trunk at ground level to chop it, see
 * {@code lumberyard.LumberjackWorkGoal}) would walk up to a tree and never actually harvest it. Root
 * cause: the original check above fires on "is my feet in/on ANY leaves block," with no distinction
 * between "stranded tens of blocks up on a canopy's flat top" (the real Guard report this was built
 * for) and "standing at completely normal ground level under a tree's own low-hanging canopy skirt,
 * leaf litter, or an overhanging branch" -- the latter is the Lumberjack's routine, expected working
 * position. Firing on every AI tick in that state calls {@code getNavigation().stop()} continuously,
 * which starves {@code LumberjackWorkGoal#tick}'s own repath logic of the uninterrupted travel time
 * it needs to ever get within harvest range, hanging the worker in a visible "walked up, then just
 * stands there" loop -- exactly this report. Fixed by only triggering once the NPC is genuinely
 * elevated above the real (leaves-ignoring) ground, not merely touching a leaf block at any height --
 * a worker stranded high in a canopy will clear this by a wide margin, while one at normal ground
 * level under a low canopy/litter never will.
 */
public final class NpcTreetopEscape {

    // How far above the real ground (MOTION_BLOCKING_NO_LEAVES) an NPC's feet must be, on top of
    // standing on/in a leaves block, before this counts as "stranded on a canopy" rather than
    // "working at normal ground level under low-hanging leaves/litter." Deliberately smaller than
    // GuardEntity's own 5-block UNDERGROUND_TELEPORT_THRESHOLD_BLOCKS (that's the opposite direction,
    // below real ground, and tolerates terrain irregularity differently) -- a genuinely stranded NPC
    // reported "tens of blocks" up, while a real tree's lowest leaves rarely sit more than 1-2 blocks
    // above the ground a worker stands on beside its trunk.
    private static final int MIN_STRANDED_ELEVATION_BLOCKS = 3;

    private NpcTreetopEscape() {
    }

    public static void check(Mob npc, ServerLevel level) {
        int blockX = Mth.floor(npc.getX());
        int blockZ = Mth.floor(npc.getZ());
        int feetY = Mth.floor(npc.getY());
        BlockPos feetPos = new BlockPos(blockX, feetY, blockZ);
        if (!level.getBlockState(feetPos).is(BlockTags.LEAVES) && !level.getBlockState(feetPos.below()).is(BlockTags.LEAVES)) {
            return;
        }
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ);
        if (feetY - surfaceY < MIN_STRANDED_ELEVATION_BLOCKS) {
            return;
        }
        npc.getNavigation().stop();
        npc.teleportTo(blockX + 0.5, surfaceY, blockZ + 0.5);
    }
}
