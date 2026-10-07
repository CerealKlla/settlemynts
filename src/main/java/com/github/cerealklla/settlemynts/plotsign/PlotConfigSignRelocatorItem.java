package com.github.cerealklla.settlemynts.plotsign;

import java.util.UUID;

import com.github.cerealklla.blueprynts.api.PlotArea;
import com.github.cerealklla.settlemynts.registration.ModBlocks;
import com.github.cerealklla.settlemynts.registration.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * "Relocate Plot Sign" hand-off item -- mirrors Blueprynts' {@code SupplyBoxLocatorItem} closely,
 * including deliberately being a real {@link BlockItem} (letting vanilla's own well-tested {@code
 * BlockItem#place} pipeline do the actual world mutation/item consumption) rather than a hand-rolled
 * {@code Item#useOn} -- that class's own doc records a real, twice-broken first attempt at hand-
 * rolling this exact kind of item, a lesson worth not re-learning here.
 *
 * <p><b>No Ghost-indicator placement constraint</b> (design doc Section 14a, explicit contrast with
 * the Building Supply Box's own edge-cell-only relocation) -- the sign can go anywhere within the
 * plot's own real {@link PlotArea} bounds, checked directly against {@link
 * PendingPlotConfigSignRelocation#peekPlotArea}, no live outline preview.
 */
public class PlotConfigSignRelocatorItem extends BlockItem {

    private static final long GRACE_TICKS = 60; // 3 seconds.

    public PlotConfigSignRelocatorItem(Properties properties) {
        super(ModBlocks.PLOT_CONFIG_SIGN.get(), properties);
    }

    @Override
    protected BlockState getPlacementState(BlockPlaceContext context) {
        UUID plotId = context.getItemInHand().get(ModItems.PLOT_CONFIG_SIGN_LOCATOR_PLOT_ID);
        if (plotId == null) {
            warnServerSide(context, "This Plot Config Sign Locator isn't bound to a plot.");
            return null;
        }
        Direction facing = PendingPlotConfigSignRelocation.peekFacing(plotId);
        if (facing == null) {
            warnServerSide(context, "This Plot Config Sign Locator has already been used.");
            return null;
        }
        PlotArea plotArea = PendingPlotConfigSignRelocation.peekPlotArea(plotId);
        BlockPos pos = context.getClickedPos();
        if (plotArea != null && !plotArea.contains(pos.getX(), pos.getZ())) {
            warnServerSide(context, "That's outside the plot -- the Plot Config Sign must stay within it.");
            return null;
        }
        return getBlock().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, facing);
    }

    @Override
    protected boolean updateCustomBlockEntityTag(BlockPos pos, Level level, Player player, ItemStack stack, BlockState state) {
        UUID plotId = stack.get(ModItems.PLOT_CONFIG_SIGN_LOCATOR_PLOT_ID);
        if (plotId != null && level instanceof ServerLevel serverLevel) {
            restoreAt(serverLevel, plotId, pos, state.getValue(HorizontalDirectionalBlock.FACING));
            if (player != null) {
                player.sendSystemMessage(Component.literal("Plot Config Sign repositioned."));
            }
        }
        return super.updateCustomBlockEntityTag(pos, level, player, stack, state);
    }

    /** Shared by both a real player placement ({@link #updateCustomBlockEntityTag}) and an auto-cancel ({@link #cancelIfHeld}) -- places the block and restores the sign's stashed identity onto it. */
    private static void restoreAt(ServerLevel level, UUID plotId, BlockPos pos, Direction facing) {
        level.setBlock(pos, ModBlocks.PLOT_CONFIG_SIGN.get().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, facing), 3);
        if (level.getBlockEntity(pos) instanceof PlotConfigSignBlockEntity sign) {
            PendingPlotConfigSignRelocation.applyTo(sign, plotId);
            PlotConfigSignIndex.get(level.getServer()).put(plotId, net.minecraft.core.GlobalPos.of(level.dimension(), pos));
        }
    }

    /**
     * Auto-cancels a held, bound, not-yet-placed Plot Config Sign Locator once the player leaves the
     * plot it belongs to -- real playtest report, 2026-09-30: "I tried moving the plot sign again and
     * it let me this time, but it also let me walk away with it in my inventory and never canceled
     * the move." Mirrors Blueprynts' {@code SupplyBoxLocatorItem#cancelIfHeld} exactly, including
     * restoring the sign at the exact position it was removed from.
     *
     * @param force skips the bounds check -- used by death/logout cancellation, which must cancel
     *              unconditionally rather than only once the player happens to be outside the plot.
     */
    public static void cancelIfHeld(ServerLevel level, Player player, ItemStack stack, boolean force, java.util.function.Consumer<ItemStack> clearStack) {
        UUID plotId = stack.get(ModItems.PLOT_CONFIG_SIGN_LOCATOR_PLOT_ID);
        if (plotId == null) {
            return;
        }
        if (!force) {
            Long grantedAt = stack.get(ModItems.PLOT_CONFIG_SIGN_LOCATOR_GRANTED_AT);
            if (grantedAt != null && level.getGameTime() - grantedAt < GRACE_TICKS) {
                return;
            }
        }
        Direction facing = PendingPlotConfigSignRelocation.peekFacing(plotId);
        BlockPos originalPos = PendingPlotConfigSignRelocation.peekOriginalPos(plotId);
        if (facing == null || originalPos == null) {
            return; // Already used, or nothing pending -- not this item's job to touch.
        }
        if (!force) {
            com.github.cerealklla.blueprynts.api.PlotArea plotArea = PendingPlotConfigSignRelocation.peekPlotArea(plotId);
            if (plotArea == null || plotArea.contains(player.blockPosition().getX(), player.blockPosition().getZ())) {
                return; // Unbound (no known plot bounds) or still inside it -- nothing to cancel yet.
            }
        }
        restoreAt(level, plotId, originalPos, facing);
        clearStack.accept(stack);
        player.sendSystemMessage(Component.literal("Reposition canceled -- the Plot Config Sign has been returned to its original spot."));
    }

    private static void warnServerSide(BlockPlaceContext context, String message) {
        if (context.getLevel() instanceof ServerLevel && context.getPlayer() != null) {
            context.getPlayer().sendSystemMessage(Component.literal(message));
        }
    }

    /** A fresh, bound Plot Config Sign Locator stack for {@code plotId} -- the sign itself must already have been removed and stashed in {@link PendingPlotConfigSignRelocation} before this is granted. */
    public static ItemStack grantFor(UUID plotId, long gameTime) {
        ItemStack stack = new ItemStack(ModBlocks.PLOT_CONFIG_SIGN_LOCATOR_ITEM.get());
        stack.set(ModItems.PLOT_CONFIG_SIGN_LOCATOR_PLOT_ID, plotId);
        stack.set(ModItems.PLOT_CONFIG_SIGN_LOCATOR_GRANTED_AT, gameTime);
        return stack;
    }
}
