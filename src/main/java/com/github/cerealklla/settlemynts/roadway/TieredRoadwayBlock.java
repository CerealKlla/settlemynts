package com.github.cerealklla.settlemynts.roadway;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

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

    public TieredRoadwayBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(TIER, 3));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(TIER);
    }
}
