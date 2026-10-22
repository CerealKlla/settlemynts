package com.github.cerealklla.settlemynts.watchtower;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
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

    /**
     * Real correction, 2026-10-22 (standing rule: "at all costs avoid non-event-driven chron jobs")
     * -- this block has no {@code BlockEntityTicker} of any kind. {@link #tick} (vanilla's own
     * scheduled-tick callback, the same mechanism {@code RedstoneLampBlock} uses for its delayed
     * turn-off, confirmed against the decompiled source) fires only at an exact future tick this
     * block entity itself explicitly scheduled -- either "retry identity resolution" or "the current
     * fuel batch just ran out" -- never on a continuous per-tick basis.
     */
    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (level.getBlockEntity(pos) instanceof WatchTowerBeaconBlockEntity beacon) {
            beacon.onScheduledTick(level);
        }
    }

    /**
     * Explicit user request, 2026-10-22: "if I use redstone can I toggle it remotely? ... it should
     * toggle the same way as the UI button" -- powered sets {@code manuallyEnabled} true, unpowered
     * sets it false, the exact same flag/method {@code SetWatchTowerEnabledPayload}'s own handler
     * flips, so whichever happened most recently (a redstone change or a GUI click) simply wins; no
     * separate "locked by redstone" concept. Immediate, no delayed-off quirk -- that's specific to
     * {@code RedstoneLampBlock}'s own visual choice (confirmed against the decompiled source before
     * writing this), not something asked for here.
     */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
                                    net.minecraft.world.level.redstone.Orientation orientation, boolean movedByPiston) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof WatchTowerBeaconBlockEntity beacon) {
            boolean powered = level.hasNeighborSignal(pos);
            if (powered != beacon.manuallyEnabled()) {
                beacon.setManuallyEnabled(powered);
            }
        }
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
