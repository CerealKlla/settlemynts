package com.github.cerealklla.settlemynts.murmur;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The Murmur Tower's own structure marker -- indestructible, never player-placed, placed directly
 * inside its Blueprint like {@code watchtower.WatchTowerBeaconBlock}. No menu/GUI and no fuel --
 * always broadcasting once bound to a built Tier, see {@code MurmurTowerBlockEntity}.
 */
public class MurmurTowerBlock extends Block implements EntityBlock {

    public static final MapCodec<MurmurTowerBlock> CODEC = simpleCodec(MurmurTowerBlock::new);

    public MurmurTowerBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MurmurTowerBlockEntity(pos, state);
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (level.getBlockEntity(pos) instanceof MurmurTowerBlockEntity tower) {
            tower.onScheduledTick(level);
        }
    }
}
