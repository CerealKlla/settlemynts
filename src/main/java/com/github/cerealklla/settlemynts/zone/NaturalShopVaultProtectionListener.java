package com.github.cerealklla.settlemynts.zone;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;

/**
 * Blocks both breaking and right-click-opening a natural settlement's underground shop vault chest
 * (see {@link NaturalShopVault}) -- explicit user request, 2026-10-08: "a player can't mess with them
 * if they find one," despite it being an entirely ordinary vanilla Chest otherwise (so the rest of
 * the Shop stock-scanning code never has to know these are special). Checked against a small
 * in-memory cache of {@link NaturalShopVaultIndex}'s own positions, refreshed on each check -- vaults
 * are created rarely (once per settlement per zone type, ever), so re-reading the index's small list
 * every interaction/break is cheap.
 */
public final class NaturalShopVaultProtectionListener {

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (isVault(level, event.getPos())) {
            event.setCanceled(true);
            event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS_SERVER);
        }
    }

    @SubscribeEvent
    public void onBreakBlock(BreakBlockEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (isVault(level, event.getPos())) {
            event.setCanceled(true);
        }
    }

    private boolean isVault(ServerLevel level, BlockPos pos) {
        Set<BlockPos> positions = new HashSet<>(NaturalShopVaultIndex.get(level.getServer()).allPositions());
        return positions.contains(pos);
    }
}
