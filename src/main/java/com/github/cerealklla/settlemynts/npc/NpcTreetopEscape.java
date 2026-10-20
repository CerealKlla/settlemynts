package com.github.cerealklla.settlemynts.npc;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Shared "stuck on top of a tree" safety net for this suite's custom ground NPCs -- extracted
 * 2026-10-20 from {@code guardhouse.GuardEntity}'s own 2026-10-19 fix (see that class's own doc for
 * the full root-cause writeup: the 2026-10-17 {@code PathType.LEAVES} malus only stops a mob from
 * pathing THROUGH a tree's interior, not from climbing UP ONTO its flat canopy top, which reads as
 * ordinary walkable ground). Real report, 2026-10-20: another NPC (not a Guard) found stuck the same
 * way, confirming the gap flagged but not fixed in Guard's own decisions.md entry actually bit live.
 *
 * <p><b>Elevation guard added 2026-10-21, then corrected the same day</b> -- a Lumberjack worker
 * (whose entire job is standing right next to a tree's trunk at ground level, see {@code
 * lumberyard.LumberjackWorkGoal}) would walk up to a tree and never actually harvest it, because the
 * original check fired on "is my feet in/on ANY leaves block" with no distinction from "standing at
 * normal ground level under a tree's own low leaves." The first fix added an elevation check against
 * {@code Heightmap.Types.MOTION_BLOCKING_NO_LEAVES} -- which turned out to be the wrong reference
 * surface: that heightmap type only ignores LEAVES when scanning for the topmost solid block, but a
 * tree's own horizontal branch LOG is just as solid as real terrain, so an NPC perched on a branch
 * (real report, confirmed live via a screenshot + direct block inspection: feet in leaves, standing
 * on a log branch at y=70 with real ground not until y=62) made the heightmap itself report the
 * branch as "the surface" -- collapsing the computed elevation difference to zero and defeating the
 * whole check, including the teleport destination (which would have landed the NPC right back on the
 * same branch it was already stuck on).
 *
 * <p>Fixed by never trusting a heightmap scan here at all -- instead, scans straight down from the
 * NPC's feet counting through consecutive LEAVES/LOGS blocks (both still "part of the tree" from this
 * check's perspective, not just leaves) until hitting the first block that's neither, which is the
 * real ground/structure underneath. A short count (a block or two of leaf litter, or a worker simply
 * standing in a low branch's shadow) is normal ground-level tree work and is left alone; a long count
 * confirms the NPC is genuinely elevated on tree material with nothing real supporting it close by,
 * and the scan's own endpoint -- the real solid block it finally found -- is used as the teleport
 * destination, so the NPC always lands on verified real ground, never back on another part of the
 * same tree.
 */
public final class NpcTreetopEscape {

    // How many consecutive leaves/log blocks (tree material) an NPC may have directly beneath its
    // feet before this counts as "stranded in the tree" rather than "working at normal ground level
    // under a low branch/leaf litter." A real tree's lowest leaves/litter rarely stack more than a
    // block or two above the ground a worker stands on beside its trunk.
    private static final int MIN_STRANDED_GAP_BLOCKS = 3;

    // Bounds the downward scan against an oddly tall structure or a genuine void below (e.g. standing
    // over a ravine under the tree) -- generously covers even a very tall vanilla tree's full canopy.
    private static final int MAX_SCAN_DEPTH_BLOCKS = 48;

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
        BlockPos scan = feetPos.below();
        int gap = 0;
        while (gap < MAX_SCAN_DEPTH_BLOCKS && isTreeMaterial(level.getBlockState(scan))) {
            scan = scan.below();
            gap++;
        }
        if (gap < MIN_STRANDED_GAP_BLOCKS || gap >= MAX_SCAN_DEPTH_BLOCKS) {
            return; // Real ground close by (normal tree-base work), or no real ground found at all -- leave it alone either way.
        }
        npc.getNavigation().stop();
        npc.teleportTo(blockX + 0.5, scan.getY() + 1, blockZ + 0.5);
    }

    private static boolean isTreeMaterial(BlockState state) {
        return state.isAir() || state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS);
    }
}
