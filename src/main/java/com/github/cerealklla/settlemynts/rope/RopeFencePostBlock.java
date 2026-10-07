package com.github.cerealklla.settlemynts.rope;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A real, craftable, player-placeable Rope Fence Post -- the "custom RopeFences" the user asked for
 * (pivoting away from the old free-placement stake + auto-fill-preview plot boundary mechanic, see
 * decisions.md). Reuses vanilla {@link FenceBlock}'s own solid/collidable post shape and model
 * wholesale (v1 placeholder art, same "reuse existing geometry" convention as every other new block
 * in this suite) rather than authoring a new one, and adds {@link RopeFencePostBlockEntity} on top
 * via {@link EntityBlock} to track up to two rope links.
 *
 * <p>All the actual linking/placement logic lives on {@code RopeFencePostItem} (right-clicking an
 * existing post with an open slot is a *link*, not a re-place) -- this block itself defines no
 * {@code useWithoutItem}, so an empty-hand or wrong-item right-click is a no-op, same as vanilla
 * fences.
 *
 * <p>The same "rope, no hook, 5-block-leash-constrained placement" mechanic also drives the *ghost*
 * plot-staking version ({@code zone.GhostPlotStakeEntity}) -- that one stays a non-solid, Town-
 * Planner-only {@code Display.BlockDisplay} using this block's own default state purely for visual
 * consistency, never a real placed instance of this block (see this suite's established "a real
 * world Block can't be per-player-visible" convention).
 */
public class RopeFencePostBlock extends FenceBlock implements EntityBlock {

    public RopeFencePostBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RopeFencePostBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return null; // No periodic logic -- link state only changes on placement/linking, both explicit calls.
    }
}
