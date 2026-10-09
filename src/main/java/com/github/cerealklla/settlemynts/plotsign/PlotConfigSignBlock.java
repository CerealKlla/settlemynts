package com.github.cerealklla.settlemynts.plotsign;

import com.mojang.serialization.MapCodec;

import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.zone.PlotPermissions;
import com.github.cerealklla.settlemynts.zone.PlotRecord;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
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
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The Plot Config Sign (design doc Section 14a) -- Settlemynts-owned (mirrors the existing
 * "Blueprynts owns the box, Settlemynts owns what happens around it" boundary from Section 13's
 * trading sign). Auto-spawned by {@code PlotConfigSignSpawner} at plot Finalize time, next to (not
 * on top of) the same site the Building Supply Box anchors to -- unconditionally, even on a server
 * with no Blueprynts loaded and so no Building Supply Box at all for this plot (user correction,
 * 2026-09-30: "if a server doesn't have Blueprynts running there isn't an inherent dependency... we'd
 * still want the Plot Sign to exist"). Never player-placed except via its own {@code
 * PlotConfigSignRelocatorItem}, same "indestructible, no default BlockItem" shape as Blueprynts' own
 * {@code ConstructionBoxBlock}.
 *
 * <p>Right-clicking opens the Main Menu ({@link OpenPlotConfigSignMenuPayload}) -- "Configure Plot"
 * (-> "Relocate Plot Sign"), "Enter Shop"/"Manage Shop" (real, visible, but inert placeholders this
 * pass -- no Yconomics shop backend exists yet, per the user's own scoping: "each plot might have
 * their own menu... For now you can just make buttons that don't go anywhere"). "Configure Garrison"
 * is omitted entirely -- no Guardhouse Plot Type can exist yet.
 */
public class PlotConfigSignBlock extends HorizontalDirectionalBlock implements EntityBlock {

    public static final MapCodec<PlotConfigSignBlock> CODEC = simpleCodec(PlotConfigSignBlock::new);

    public PlotConfigSignBlock(Properties properties) {
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
        return new PlotConfigSignBlockEntity(pos, state);
    }

    @Override
    public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)
                || !(level instanceof ServerLevel serverLevel)
                || !(level.getBlockEntity(pos) instanceof PlotConfigSignBlockEntity sign)) {
            return InteractionResult.SUCCESS;
        }
        boolean canManage = false;
        boolean hasGarrison = false;
        boolean isTownHall = false;
        boolean hasConstructionBox = false;
        if (sign.settlementCoreId() != null && serverLevel.getEntity(sign.settlementCoreId()) instanceof GhostTownHallCoreEntity core) {
            PlotRecord plot = core.getPlots().stream().filter(p -> p.plotId().equals(sign.plotId())).findFirst().orElse(null);
            if (plot != null) {
                canManage = PlotPermissions.canManage(plot, core, serverPlayer.getUUID());
                hasGarrison = plot.zoneTypeId().equals(com.github.cerealklla.settlemynts.guardhouse.GuardhouseConstants.GUARDHOUSE_ZONE_TYPE_ID);
                isTownHall = plot.zoneTypeId().equals(GhostTownHallCoreEntity.TOWN_HALL_ZONE_TYPE_ID);
                hasConstructionBox = plot.constructionBoxId().isPresent();
            }
        }
        PacketDistributor.sendToPlayer(serverPlayer, new OpenPlotConfigSignMenuPayload(pos, canManage, hasGarrison, isTownHall, hasConstructionBox));
        return InteractionResult.SUCCESS_SERVER;
    }

    /**
     * Whether this sign's plot has a real shop -- neither Guardhouse nor Town Hall are shop-bearing
     * plot types (real report, 2026-10-05: Town Hall was still showing Shop buttons/prompts despite
     * being civic infrastructure, same as Guardhouse already correctly excludes) -- the same rule
     * {@link #useWithoutItem}'s button visibility already expresses, extracted so {@code
     * PlotShopProximityTicker} (the "walk within 4 blocks" prompt, 2026-10-05) can reuse it without
     * duplicating the plot-resolution logic.
     */
    public static boolean hasShop(ServerLevel serverLevel, PlotConfigSignBlockEntity sign) {
        if (sign.settlementCoreId() == null || !(serverLevel.getEntity(sign.settlementCoreId()) instanceof GhostTownHallCoreEntity core)) {
            return false;
        }
        PlotRecord plot = core.getPlots().stream().filter(p -> p.plotId().equals(sign.plotId())).findFirst().orElse(null);
        if (plot == null) {
            return false;
        }
        return !plot.zoneTypeId().equals(com.github.cerealklla.settlemynts.guardhouse.GuardhouseConstants.GUARDHOUSE_ZONE_TYPE_ID)
                && !plot.zoneTypeId().equals(GhostTownHallCoreEntity.TOWN_HALL_ZONE_TYPE_ID);
    }
}
