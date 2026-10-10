package com.github.cerealklla.settlemynts.roadway;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A plain indestructible road block whose look tracks a settlement's Town Hall Tier (added
 * 2026-10-09, explicit request: "I'd like a visual progression which is directly tied to a
 * settlement's town hall tier... only a visual change for roads"). {@link #TIER} drives the
 * blockstate's model variant (see {@code settlemynts:blockstates/roadway.json}) -- 1 = a real
 * shoveled dirt path (vanilla's own {@code dirt_path} top/side textures, matching natural villages'
 * actual unpaved look -- an earlier version used a plain {@code dirt} cube texture instead, which
 * looked wrong next to real natural-village paths; fixed 2026-10-09), 2 = cobblestone,
 * 3 = stone bricks (the pre-existing default look, kept as the default state for any already-placed
 * road predating this feature), 4 = bricks, 5 = a fancier stone/brick finish. All five share the
 * exact same behavior/properties as the original plain {@code Block} this replaces -- only the
 * state's own TIER property is new.
 *
 * <p>{@link RoadwayTierResolver} computes the correct tier at both pave-time ({@code
 * RoadwayPaver#paveEdge}) and retroactively ({@code RoadwayTierTicker}, whenever a settlement's
 * Town Hall Tier changes) -- this class itself has no logic beyond the blockstate property.
 */
public class TieredRoadwayBlock extends Block {

    public static final IntegerProperty TIER = IntegerProperty.create("tier", 1, 5);

    // Matches vanilla DirtPathBlock's own SHAPE exactly (Block.column(16.0, 0.0, 15.0)) -- Tier 1's
    // model is parented directly on minecraft:block/dirt_path, whose top face sits 1 pixel below a
    // full cube. Without a matching shape override here, this block kept reporting a full-cube shape
    // regardless of tier (the Block default), which made a neighboring block's engine-side face
    // culling wrongly treat Tier 1 as fully sealed against it -- a real, confirmed live bug
    // (2026-10-09): a 1-pixel gap at the road's edge rendered as seeing straight through the terrain,
    // since neither this block's own (correctly short) top face nor the neighbor's (wrongly culled)
    // bottom-of-side face painted anything there.
    private static final VoxelShape TIER_1_SHAPE = Block.column(16.0, 0.0, 15.0);

    public TieredRoadwayBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(TIER, 3));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(TIER);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(TIER) == 1 ? TIER_1_SHAPE : super.getShape(state, level, pos, context);
    }

    // Tells the engine to actually consult getShape() for face culling/light occlusion instead of
    // assuming a full solid cube -- same as vanilla DirtPathBlock's own override. Safe for every tier
    // (2-5 report a real full-cube shape, so this is a no-op for them).
    @Override
    protected boolean useShapeForLightOcclusion(BlockState state) {
        return state.getValue(TIER) == 1;
    }

    // Real live bug, 2026-10-09: guards got stuck/hesitated at a Tier 1 road's edge after the shape
    // override above shipped. Root cause -- BlockBehaviour's own default isPathfindable(LAND)
    // evaluates to !isCollisionShapeFullBlock(...), which treats a non-full-cube shape as OPEN space
    // (no floor there) rather than solid ground, exactly backwards for an actual walkable surface.
    // Vanilla's own DirtPathBlock hits the same thing and fixes it by hardcoding this override to
    // always report false (solid, not open) regardless of PathComputationType -- mirrored here.
    // Harmless for tiers 2-5: they're genuine full cubes, so the default formula already agreed.
    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return false;
    }
}
