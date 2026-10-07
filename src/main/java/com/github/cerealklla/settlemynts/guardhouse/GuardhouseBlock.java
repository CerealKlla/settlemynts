package com.github.cerealklla.settlemynts.guardhouse;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The Guardhouse's own structure marker (design doc Section 14a) -- indestructible, never
 * player-placed, same shape as {@code plotsign.PlotConfigSignBlock}/Blueprynts' own {@code
 * ConstructionBoxBlock}. Auto-spawned by {@link GuardhouseSpawner} at plot Finalize time, only for a
 * Guardhouse-typed plot. Right-clicking opens its own food {@link Container} directly (a plain
 * vanilla one-row chest UI) -- the garrison gear-permission configuration lives on the Plot Config
 * Sign's own "Configure Garrison" button instead, not here.
 */
public class GuardhouseBlock extends HorizontalDirectionalBlock implements EntityBlock {

    public static final MapCodec<GuardhouseBlock> CODEC = simpleCodec(GuardhouseBlock::new);

    public GuardhouseBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new GuardhouseBlockEntity(pos, state);
    }

    @Override
    public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)
                || !(level.getBlockEntity(pos) instanceof GuardhouseBlockEntity guardhouse)) {
            return InteractionResult.SUCCESS;
        }
        serverPlayer.openMenu(guardhouse);
        return InteractionResult.SUCCESS_SERVER;
    }
}
