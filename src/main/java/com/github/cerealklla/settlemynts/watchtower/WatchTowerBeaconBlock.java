package com.github.cerealklla.settlemynts.watchtower;

import com.mojang.serialization.MapCodec;

import com.github.cerealklla.settlemynts.registration.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The Watch Tower's own structure marker -- indestructible, never player-placed. Placed directly
 * inside the Watch Tower Ancestors Blueprints themselves (real report, 2026-10-22: "I wanted the
 * custom block to be placed within the blueprynt itself, like a light at the top of a Lighthouse"),
 * pasted like any other block by Blueprynts' own construction pipeline -- it has no plot/settlement
 * identity at all until {@link WatchTowerBeaconBlockEntity#tryResolveIdentity} self-resolves it a
 * few seconds after existing. Right-clicking opens the fuel UI ({@link WatchTowerBeaconMenu}).
 * Visually a plain cube (no facing needed) reusing vanilla's own
 * Furnace side/top textures plus Glowstone on the top face -- see the block model JSON.
 */
public class WatchTowerBeaconBlock extends Block implements EntityBlock {

    public static final MapCodec<WatchTowerBeaconBlock> CODEC = simpleCodec(WatchTowerBeaconBlock::new);

    public WatchTowerBeaconBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WatchTowerBeaconBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide() || type != ModBlockEntities.WATCH_TOWER_BEACON.get()) {
            return null;
        }
        return (lvl, pos, st, be) -> {
            if (be instanceof WatchTowerBeaconBlockEntity beacon && lvl instanceof ServerLevel serverLevel) {
                beacon.serverTick(serverLevel);
            }
        };
    }

    @Override
    public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)
                || !(level.getBlockEntity(pos) instanceof WatchTowerBeaconBlockEntity beacon)) {
            return InteractionResult.SUCCESS;
        }
        serverPlayer.openMenu(beacon);
        return InteractionResult.SUCCESS_SERVER;
    }
}
